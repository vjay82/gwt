/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.javac;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.dev.jjs.impl.GwtAstBuilder;
import com.google.gwt.dev.util.CompilerVersion;
import com.google.gwt.dev.util.StringInterningObjectInputStream;
import com.google.gwt.dev.util.log.perf.SimpleEvent;
import com.google.gwt.thirdparty.guava.common.annotations.VisibleForTesting;
import com.google.gwt.thirdparty.guava.common.collect.Lists;
import com.google.gwt.thirdparty.guava.common.io.Closeables;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;

/**
 * The directory containing persistent unit cache files.
 * (Helper class for {@link PersistentUnitCache}.)
 *
 * <p>Cache files are grouped into one subdirectory per day. Units are read from every retained
 * day directory but only ever written to today's, so a unit survives only as long as it keeps
 * being used; see {@link PersistentUnitCache} for how promotion works.
 *
 * <p>The same directory may be shared by compilers running in separate processes at the same time,
 * such as a build and a code server. Each cache file is written by a single process, which holds an
 * exclusive lock on it until it is closed, and deletion skips files that are still locked. Every
 * process therefore only ever reclaims files that nobody is appending to; whatever it leaves behind
 * is reclaimed later when its day directory expires.
 */
class PersistentUnitCacheDir {

  private static final String DIRECTORY_NAME = "gwt-unitCache";
  private static final String CACHE_FILE_PREFIX = "gwt-unitCache-";

  /**
   * How many day directories to keep, including today's. A cached unit is dropped once it has
   * gone unused for this many days.
   */
  private static final int RETENTION_DAYS = 3;

  private static final DateTimeFormatter DAY_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  static final String CURRENT_VERSION_CACHE_FILE_PREFIX =
      CACHE_FILE_PREFIX + CompilerVersion.getHash();

  private final TreeLogger logger;
  private final File dir;
  private final File dayDir;
  private final String filePrefix;

  /** Set by {@link #loadUnitMap} when units were read from a day directory other than today's. */
  private boolean loadedUnitsFromEarlierDays;

  // Non-null when a a cache file is open for writing. (Always true in normal operation.)
  private OpenFile openFile;

  /**
   * Finds the child directory where the cache files will be stored and opens a new cache
   * file for appending.
   */
  PersistentUnitCacheDir(TreeLogger logger, File parentDir, String cacheFilePrefix)
      throws UnableToCompleteException {
    this.logger = logger;
    this.filePrefix = CURRENT_VERSION_CACHE_FILE_PREFIX + "-" + cacheFilePrefix + "-";

    /*
     * We must canonicalize the path here, otherwise we might set cacheDirectory
     * to something like "/path/to/x/../gwt-unitCache". If this were to happen,
     * the mkdirs() call below would create "/path/to/gwt-unitCache" but
     * not "/path/to/x".
     * Further accesses via the uncanonicalized path will fail if "/path/to/x"
     * had not been created by other means.
     *
     * Fixes issue 6443
     */
    try {
      parentDir = parentDir.getCanonicalFile();
    } catch (IOException e) {
      logger.log(TreeLogger.WARN, "Can't get canonical directory for "
          + parentDir.getAbsolutePath(), e);
      throw new UnableToCompleteException();
    }

    dir = chooseCacheDir(parentDir);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      logger.log(TreeLogger.WARN, "Can't create directory: " + dir.getAbsolutePath());
      throw new UnableToCompleteException();
    }

    if (!dir.canRead()) {
      logger.log(Type.WARN, "Can't read directory: " + dir.getAbsolutePath());
      throw new UnableToCompleteException();
    }

    dayDir = new File(dir, LocalDate.now().format(DAY_DIR_FORMAT));
    if (!dayDir.isDirectory() && !dayDir.mkdirs()) {
      logger.log(TreeLogger.WARN, "Can't create directory: " + dayDir.getAbsolutePath());
      throw new UnableToCompleteException();
    }

    deleteExpiredDayDirs();

    logger.log(TreeLogger.TRACE, "Persistent unit cache dir set to: " + dayDir.getAbsolutePath());

    openFile = new OpenFile(logger, createEmptyCacheFile(logger, dayDir, filePrefix));
  }

  /**
   * Returns the absolute path of the directory where cache files are written.
   */
  String getPath() {
    return dayDir.getAbsolutePath();
  }

  /**
   * Returns true when the in-memory cache holds units that only exist in an earlier day's
   * directory, meaning they need to be promoted into today's directory to survive.
   */
  synchronized boolean hasUnitsFromEarlierDays() {
    return loadedUnitsFromEarlierDays;
  }

  /**
   * Returns the number of files written to today's cache directory and closed.
   */
  synchronized int getClosedCacheFileCount() {
    return selectClosedFiles(listFiles(dayDir, filePrefix)).size();
  }

  /**
   * Load everything cached on disk into memory, from today's directory and every retained
   * earlier day's directory.
   */
  synchronized void loadUnitMap(PersistentUnitCache destination) {
    if (logger.isLoggable(TreeLogger.TRACE)) {
      logger.log(TreeLogger.TRACE, "Looking for previously cached Compilation Units in "
          + dir.getAbsolutePath());
    }
    try (SimpleEvent ignored = new SimpleEvent("PersistentUnitCacheDir.loadUnitMap")) {
      // Oldest day first so that newer copies of a unit overwrite older ones.
      for (File eachDayDir : listDayDirs()) {
        List<File> files = selectClosedFiles(listFiles(eachDayDir, filePrefix));
        if (files.isEmpty()) {
          continue;
        }
        if (!eachDayDir.equals(dayDir)) {
          loadedUnitsFromEarlierDays = true;
        }
        for (File cacheFile : files) {
          loadOrDeleteCacheFile(cacheFile, destination);
        }
      }
    }
  }

  /**
   * Deletes every closed cache file of ours in today's directory. Called after the surviving units
   * have been rewritten into a fresh file, so earlier days are left alone until they expire.
   */
  synchronized void deleteClosedCacheFiles() {
    deleteClosedCacheFilesIn(dayDir);
    loadedUnitsFromEarlierDays = false;
  }

  /**
   * Deletes every cache file of ours in every day directory except for the currently open file.
   */
  synchronized void deleteAllCacheFiles() {
    for (File eachDayDir : listDayDirs()) {
      deleteClosedCacheFilesIn(eachDayDir);
    }
    loadedUnitsFromEarlierDays = false;
  }

  private void deleteClosedCacheFilesIn(File fromDir) {
    logger.log(TreeLogger.TRACE, "Deleting cache files from " + fromDir);

    try (SimpleEvent ignored = new SimpleEvent("PersistentUnitCacheDir.deleteClosedCacheFiles")) {
      // Only our own files: the rest belong to a compiler configured with other options, which may
      // be running right now. Unreadable generations are reclaimed by deleteStaleGenerationsIn.
      List<File> ourFiles = listFiles(fromDir, filePrefix);
      int deleteCount = 0;
      for (File candidate : ourFiles) {
        if (deleteUnlessInUse(candidate)) {
          deleteCount++;
        }
      }

      logger.log(TreeLogger.TRACE, "Deleted " + deleteCount + " cache files from " + fromDir);
    }
  }

  /**
   * Removes day directories outside the retention window, along with cache files left directly
   * in the cache root by earlier versions of this class.
   */
  private void deleteExpiredDayDirs() {
    LocalDate oldestToKeep = LocalDate.now().minusDays(RETENTION_DAYS - 1);

    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      if (child.isDirectory()) {
        LocalDate day = parseDayDirName(child.getName());
        if (day == null) {
          continue;
        }
        if (day.isBefore(oldestToKeep)) {
          logger.log(Type.TRACE, "Deleting expired unit cache directory: " + child);
          deleteRecursively(child);
        } else {
          deleteStaleGenerationsIn(child);
        }
      } else if (child.getName().startsWith(CACHE_FILE_PREFIX)) {
        // Left over from the flat layout used before day directories were introduced.
        deleteUnlessInUse(child);
      }
    }
  }

  /**
   * Deletes cache files that this compiler can never read back, because they were written by a
   * compiler built from a different jar. {@link CompilerVersion} hashes the whole gwt-dev jar, so
   * every rebuild of the compiler orphans the previous generation; without this they would occupy
   * the disk until their day directory expires.
   */
  private void deleteStaleGenerationsIn(File fromDir) {
    for (File candidate : listFiles(fromDir, CACHE_FILE_PREFIX)) {
      if (!candidate.getName().startsWith(CURRENT_VERSION_CACHE_FILE_PREFIX)) {
        deleteUnlessInUse(candidate);
      }
    }
  }

  /**
   * Returns the retained day directories, oldest first, always including today's.
   */
  private List<File> listDayDirs() {
    List<File> out = Lists.newArrayList();
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        if (child.isDirectory() && parseDayDirName(child.getName()) != null) {
          out.add(child);
        }
      }
    }
    if (!out.contains(dayDir)) {
      out.add(dayDir);
    }
    Collections.sort(out);
    return out;
  }

  private static LocalDate parseDayDirName(String name) {
    try {
      return LocalDate.parse(name, DAY_DIR_FORMAT);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private boolean deleteRecursively(File toDelete) {
    boolean complete = true;
    File[] children = toDelete.listFiles();
    if (children != null) {
      for (File child : children) {
        complete &= deleteRecursively(child);
      }
    }
    if (toDelete.isFile() && (isOpen(toDelete) || isBeingWrittenByAnotherProcess(toDelete))) {
      logger.log(Type.TRACE, "Keeping cache file that is still open: " + toDelete);
      return false;
    }
    if (!complete) {
      // Something below is still in use, so this directory can't go yet either.
      return false;
    }
    if (!toDelete.delete()) {
      logger.log(Type.WARN, "Unable to delete: " + toDelete);
      return false;
    }
    return true;
  }

  /**
   * Closes the current cache file and opens a new one.
   */
  synchronized void rotate() throws UnableToCompleteException {
    logger.log(Type.TRACE, "Rotating persistent unit cache");
    if (openFile != null) {
      openFile.close(logger);
      openFile = null;
    }
    openFile = new OpenFile(logger, createEmptyCacheFile(logger, dayDir, filePrefix));
  }

  /**
   * Deletes the given file unless it's still being appended to, either by this process or by a
   * compiler running in another one.
   */
  synchronized boolean deleteUnlessInUse(File cacheFile) {
    if (isOpen(cacheFile) || isBeingWrittenByAnotherProcess(cacheFile)) {
      logger.log(Type.TRACE, "Keeping cache file that is still open: " + cacheFile);
      return false;
    }
    logger.log(Type.TRACE, "Deleting file: " + cacheFile);
    boolean deleted = cacheFile.delete();
    if (!deleted) {
      logger.log(Type.WARN, "Unable to delete file: " + cacheFile);
    }
    return deleted;
  }

  /**
   * Returns true if some other process has this cache file open for writing. Writers hold an
   * exclusive lock for as long as their file is open, so a file that can't be locked belongs to a
   * compiler that is still running and must be left alone.
   */
  private boolean isBeingWrittenByAnotherProcess(File cacheFile) {
    if (!cacheFile.isFile()) {
      return false;
    }
    // Opened without CREATE, so a file that someone else already deleted isn't recreated here.
    try (FileChannel channel = FileChannel.open(cacheFile.toPath(), StandardOpenOption.WRITE)) {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        return true;
      }
      lock.release();
      return false;
    } catch (OverlappingFileLockException e) {
      return true;
    } catch (IOException e) {
      logger.log(Type.TRACE, "Assuming cache file is in use because it can't be locked: "
          + cacheFile, e);
      return true;
    }
  }

  /**
   * Writes a compilation unit to the disk cache.
   */
  synchronized void writeUnit(CompilationUnit unit) throws UnableToCompleteException {
    if (openFile == null) {
      logger.log(Type.TRACE, "Skipped writing compilation unit to cache because no file is open");
      return;
    }
    openFile.writeUnit(logger, unit);
  }

  /**
   * Closes the file where cache entries are written.
   * (This should only be called at shutdown.)
   */
  synchronized void closeCurrentFile() {
    if (openFile != null) {
      openFile.close(logger);
      openFile = null;
    }
  }

  @VisibleForTesting
  static File chooseCacheDir(File parentDir) {
    return new File(parentDir, DIRECTORY_NAME);
  }

  /**
   * Returns the directory that cache files are written to today.
   */
  @VisibleForTesting
  static File chooseDayDir(File parentDir) {
    return new File(chooseCacheDir(parentDir), LocalDate.now().format(DAY_DIR_FORMAT));
  }

  private boolean isOpen(File f) {
    return openFile != null && openFile.file.equals(f);
  }

  /**
   * Loads all the units in a cache file into the given cache.
   * Delete it if unable to read it.
   */
  private void loadOrDeleteCacheFile(File cacheFile, PersistentUnitCache destination) {
    FileInputStream fis = null;
    BufferedInputStream bis = null;
    ObjectInputStream inputStream = null;

    boolean ok = false;
    int unitsLoaded = 0;
    try {
      fis = new FileInputStream(cacheFile);
      bis = new BufferedInputStream(fis);
      /*
       * It is possible for the next call to throw an exception, leaving
       * inputStream null and fis still live.
       */
      inputStream = new StringInterningObjectInputStream(bis);

      // Read objects until we get an EOF exception.
      while (true) {
        CachedCompilationUnit unit = (CachedCompilationUnit) inputStream.readObject();
        if (unit == null) {
          // Won't normally get here. Not sure why this check was here before.
          logger.log(Type.WARN, "unexpected null in cache file: " + cacheFile);
          break;
        }
        if (unit.getTypesSerializedVersion() != GwtAstBuilder.getSerializationVersion()) {
          continue;
        }
        destination.maybeAddLoadedUnit(unit);
        unitsLoaded++;
      }

    } catch (EOFException ignored) {
      // This is a normal exit. Go on to the next file.
      ok = true;
    } catch (IOException e) {
      logger.log(TreeLogger.TRACE, "Ignoring and deleting cache log "
          + cacheFile.getAbsolutePath() + " due to read error.", e);
    } catch (ClassNotFoundException e) {
      logger.log(TreeLogger.TRACE, "Ignoring and deleting cache log "
          + cacheFile.getAbsolutePath() + " due to deserialization error.", e);
    } finally {
      Closeables.closeQuietly(inputStream);
      Closeables.closeQuietly(bis);
      Closeables.closeQuietly(fis);
    }

    if (ok) {
      logger.log(TreeLogger.TRACE, "Loaded " + unitsLoaded +
          " units from cache file: " + cacheFile.getName());
    } else {
      deleteUnlessInUse(cacheFile);
      logger.log(TreeLogger.TRACE, "Loaded " + unitsLoaded +
          " units from invalid cache file before deleting it: " + cacheFile.getName());
    }
  }

  /**
   * Lists files in the cache directory that start with the given prefix.
   *
   * <p>The files will be sorted according to {@link java.io.File#compareTo}, which
   * differs on Unix versus Windows, but is good enough to sort by age
   * for the names we use.</p>
   */
  private List<File> listFiles(File fromDir, String prefix) {
    File[] files = fromDir.listFiles();
    if (files == null) {
      // Shouldn't happen, just satisfying null check warning.
      return Collections.emptyList();
    }
    List<File> out = Lists.newArrayList();
    for (File file : files) {
      if (file.getName().startsWith(prefix)) {
        out.add(file);
      }
    }
    Collections.sort(out);
    return out;
  }

  /**
   * Removes the currently open file from a list of files.
   * @return the new list.
   */
  private List<File> selectClosedFiles(Iterable<File> fileList) {
    List<File> closedFiles = Lists.newArrayList();
    for (File file : fileList) {
      if (!isOpen(file)) {
        closedFiles.add(file);
      }
    }
    return closedFiles;
  }

  /**
   * Creates a new, empty file with a name based on the current system time.
   */
  private static File createEmptyCacheFile(TreeLogger logger, File dir, String filePrefix)
      throws UnableToCompleteException {
    File newFile = null;
    long timestamp = System.currentTimeMillis();
    try {
      do {
        newFile = new File(dir, filePrefix + String.format("%016X", timestamp++));
      } while (!newFile.createNewFile());
    } catch (IOException ex) {
      logger.log(TreeLogger.WARN, "Can't create new cache log file "
          + newFile.getAbsolutePath() + ".", ex);
      throw new UnableToCompleteException();
    }

    if (!newFile.canWrite()) {
      logger.log(TreeLogger.WARN, "Can't write to new cache log file "
          + newFile.getAbsolutePath() + ".");
      throw new UnableToCompleteException();
    }

    return newFile;
  }

  /**
   * The current file and stream being written to by the persistent unit cache, if any.
   *
   * <p>Not thread safe. (The parent class handles concurrency.)
   */
  private static class OpenFile {
    private final File file;
    private final FileLock lock;
    private final ObjectOutputStream stream;
    private int unitsWritten = 0;

    /**
     * Opens a file for writing compilation units.
     * Overwrites the file (it's typically empty).
     * A cache file may not already be open.
     */
    OpenFile(TreeLogger logger, File toOpen)
        throws UnableToCompleteException {
      logger.log(Type.TRACE, "Opening cache file: " + toOpen);
      FileOutputStream fileStream = openFileStream(logger, toOpen);

      FileLock fileLock;
      ObjectOutputStream newStream;
      try {
        // Held until close so that a compiler in another process doesn't delete this file while
        // it is still being appended to.
        fileLock = fileStream.getChannel().tryLock();
        newStream = new ObjectOutputStream(new BufferedOutputStream(fileStream));
      } catch (IOException | OverlappingFileLockException e) {
        logger.log(Type.ERROR, "Can't open persistent unit cache file", e);
        closeAndDelete(fileStream, toOpen);
        throw new UnableToCompleteException();
      }

      this.file = toOpen;
      this.lock = fileLock;
      this.stream = newStream;
      unitsWritten = 0;
    }

    /**
     * Writes a compilation unit to the currently open file, if any.
     * @return true if written
     * @throws UnableToCompleteException if the file was open but we can't append.
     */
    boolean writeUnit(TreeLogger logger, CompilationUnit unit)
        throws UnableToCompleteException {
      try {
        stream.writeObject(unit);
        unitsWritten++;
        return true;
      } catch (IOException e) {
        logger.log(TreeLogger.ERROR, "Error saving compilation unit to cache file: " + file, e);
        throw new UnableToCompleteException();
      }
    }

    /**
     * Closes the current file and deletes it if it's empty.
     */
    void close(TreeLogger logger) {
      logger.log(Type.TRACE,
          "Closing cache file: " + file + " (" + unitsWritten + " units written)");

      try {
        if (lock != null) {
          lock.release();
        }
      } catch (IOException e) {
        logger.log(Type.WARN, "Error unlocking compilation unit cache file " + file, e);
      }

      try {
        stream.close();
      } catch (IOException e) {
        logger.log(Type.WARN, "Error closing compilation unit cache file " + file, e);
      }

      if (unitsWritten == 0) {
        // Remove useless empty file.
        logger.log(Type.TRACE, "Deleting empty file: " + file);
        boolean deleted = file.delete();
        if (!deleted) {
          logger.log(Type.INFO, "Couldn't delete persistent unit cache file: " + file);
        }
      }
    }

    private static FileOutputStream openFileStream(TreeLogger logger, File file)
        throws UnableToCompleteException {
      try {
        return new FileOutputStream(file);
      } catch (IOException e) {
        logger.log(Type.ERROR, "Can't open persistent unit cache file", e);
        throw new UnableToCompleteException();
      }
    }

    private static void closeAndDelete(FileOutputStream fileStream, File file) {
      try {
        fileStream.close();
        if (file.exists()) {
          Files.delete(file.toPath());
        }
      } catch (IOException ignored) {
        // We can't handle this, and already logged an error
      }
    }
  }
}
