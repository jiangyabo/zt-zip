/**
 *    Copyright (C) 2012 ZeroTurnaround LLC <support@zeroturnaround.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.zeroturnaround.zip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

/**
 * Fluent api for zip handling.
 *
 * @author shelajev
 *
 */
public class Zips {

  /**
   * Source archive. TODO: make it work on streams
   */
  private final File src;

  /**
   * Optional destination archive, if null, src will be overwrittene
   */
  private File dest;

  /**
   * Charset to use for entry names
   */
  private Charset charset;

  /**
   * Flag to carry timestamps of entries on.
   */
  private boolean preserveTimestamps;

  /**
   * List<ZipEntrySource>
   */
  private List changedEntries = new ArrayList();

  /**
   * List<String>
   */
  private Set removedEntries = new HashSet();

  /**
   * List<ZipEntryTransformerEntry>
   */
  private List transformers = new ArrayList();

  /*
   * If you want many name mappers here, you can create some compound instance that knows if
   * it wants to stop after first successfull mapping or go through all transformations, if null means
   * stop and ignore entry or that name mapper didn't know how to transform, etc.
   */
  private NameMapper nameMapper;

  private Zips(File src) {
    this.src = src;
  }

  /**
   * Static factory method to obtain an instance of Zips.
   *
   * @param src zip file to process
   * @return instance of Zips
   */
  public static Zips get(File src) {
    return new Zips(src);
  }

  /**
   * Static factory method to obtain an instance of Zips without source file.
   * See {@link #get(File src)}.
   *
   * @return instance of Zips
   */
  public static Zips create() {
    return new Zips(null);
  }

  /**
   * Specifies an entry to add or change to the output when this Zips executes.
   * Adding takes precedence over removal of entries.
   *
   * @param entry entry to add
   * @return this Zips for fluent api
   */
  public Zips addEntry(ZipEntrySource entry) {
    this.changedEntries.add(entry);
    return this;
  }

  /**
   * Specifies entries to add or change to the output when this Zips executes.
   * Adding takes precedence over removal of entries.
   *
   * @param entries entries to add
   * @return this Zips for fluent api
   */
  public Zips addEntries(ZipEntrySource[] entries) {
    this.changedEntries.addAll(Arrays.asList(entries));
    return this;
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @return this Zips for fluent api
   */
  public Zips addFile(File file) {
    return addFile(file, false, null);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param preserveRoot if file is a directory, true indicates we want to preserve this dir in the zip.
   *          otherwise children of the file are added directly under root.
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, boolean preserveRoot) {
    return addFile(file, preserveRoot, null);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param filter a filter to accept files for adding, null means all files are accepted
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, FileFilter filter) {
    return this.addFile(file, false, filter);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param preserveRoot if file is a directory, true indicates we want to preserve this dir in the zip.
   *          otherwise children of the file are added directly under root.
   * @param filter a filter to accept files for adding, null means all files are accepted
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, boolean preserveRoot, FileFilter filter) {
    if (!file.isDirectory()) {
      this.changedEntries.add(new FileSource(file.getName(), file));
      return this;
    }
    Collection files = FileUtils.listFiles(file, null, true);
    for (Iterator iter = files.iterator(); iter.hasNext();) {
      File entryFile = (File) iter.next();
      if (filter != null && !filter.accept(entryFile)) {
        continue;
      }
      String entryPath = getRelativePath(file, entryFile);
      if (preserveRoot) {
        entryPath = file.getName() + entryPath;
      }
      if (entryPath.startsWith("/")) {
        entryPath = entryPath.substring(1);
      }
      this.changedEntries.add(new FileSource(entryPath, entryFile));
    }
    return this;
  }

  private String getRelativePath(File parent, File file) {
    String parentPath = parent.getPath();
    String filePath = file.getPath();
    if (!filePath.startsWith(parentPath)) {
      throw new IllegalArgumentException("File " + file + " is not a child of " + parent);
    }
    return filePath.substring(parentPath.length());
  }

  /**
   * Specifies an entry to remove to the output when this Zips executes.
   *
   * @param entry path of the entry to remove
   * @return this Zips for fluent api
   */
  public Zips removeEntry(String entry) {
    this.removedEntries.add(entry);
    return this;
  }

  /**
   * Specifies entries to remove to the output when this Zips executes.
   *
   * @param entries paths of the entry to remove
   * @return this Zips for fluent api
   */
  public Zips removeEntries(String[] entries) {
    this.removedEntries.addAll(Arrays.asList(entries));
    return this;
  }

  /**
   * Enables timestamp preserving for this Zips execution
   *
   * @return this Zips for fluent api
   */
  public Zips preserveTimestamps() {
    this.preserveTimestamps = true;
    return this;
  }

  /**
   * Specifies timestamp preserving for this Zips execution
   *
   * @param preserve flag to preserve timestamps
   * @return this Zips for fluent api
   */
  public Zips setPreserveTimestamps(boolean preserve) {
    this.preserveTimestamps = true;
    return this;
  }

  /**
   * Specifies charset for this Zips execution
   *
   * @param charset charset to use
   * @return this Zips for fluent api
   */
  public Zips charset(Charset charset) {
    this.charset = charset;
    return this;
  }

  /**
   * Specifies destination file for this Zips execution,
   * if destination is null (default value), then source file will be overwritten.
   * Temporary file will be used as destination and then written over the source to
   * create an illusion if inplace action.
   *
   * @param destination charset to use
   * @return this Zips for fluent api
   */
  public Zips destination(File destination) {
    this.dest = destination;
    return this;
  }

  /**
   *
   * @param nameMapper to use when processing entries
   * @return this Zips for fluent api
   */
  public Zips nameMapper(NameMapper nameMapper) {
    this.nameMapper = nameMapper;
    return this;
  }

  /**
   * @return true if destination is not specified.
   */
  private boolean isInPlace() {
    return dest == null;
  }

  /**
   * Registers a transformer for a given entry.
   *
   * @param path entry to transform
   * @param transformer transformer for the entry
   * @return this Zips for fluent api
   */
  public Zips addTransformer(String path, ZipEntryTransformer transformer) {
    this.transformers.add(new ZipEntryTransformerEntry(path, transformer));
    return this;
  }

  /**
   * Iterates through source Zip entries removing or changing them according to
   * set parameters.
   */
  public void process() {
    if (src == null && dest == null) {
      throw new IllegalArgumentException("Source and destination shouldn't be null together");
    }

    ZipEntryTransformerEntry[] transformersArray = getTransformersArray();

    File destinationZip = null;
    try {
      destinationZip = isInPlace() ? File.createTempFile("zips", ".zip") : dest;
      final ZipOutputStream out = createZipOutputStream(new BufferedOutputStream(new FileOutputStream(destinationZip)));
      try {

        ZipEntryOrInfoAdapter zipEntryAdapter = new ZipEntryOrInfoAdapter(new CopyingCallback(transformersArray, out), null);
        iterateChangedAndAdded(zipEntryAdapter);
        iterateExistingExceptRemoved(zipEntryAdapter);

        handleInPlaceActions(destinationZip);
      }
      finally {
        IOUtils.closeQuietly(out);
      }
    }
    catch (IOException e) {
      throw ZipUtil.rethrow(e);
    }
    finally {
      if (isInPlace()) {
        // destinationZip is a temporary file
        FileUtils.deleteQuietly(destinationZip);
      }
    }
  }

  /**
   * Reads the source ZIP file and executes the given callback for each entry.
   * <p>
   * For each entry the corresponding input stream is also passed to the callback. If you want to stop the loop then throw a ZipBreakException.
   *
   * This method is charset aware and uses Zips.charset.
   *
   * @param zipEntryCallback
   *          callback to be called for each entry.
   *
   * @see ZipEntryCallback
   *
   */
  public void iterate(ZipEntryCallback zipEntryCallback) {
    ZipEntryOrInfoAdapter zipEntryAdapter = new ZipEntryOrInfoAdapter(zipEntryCallback, null);
    iterateChangedAndAdded(zipEntryAdapter);
    iterateExistingExceptRemoved(zipEntryAdapter);
  }

  /**
   * Scans the source ZIP file and executes the given callback for each entry.
   * <p>
   * Only the meta-data without the actual data is read. If you want to stop the loop then throw a ZipBreakException.
   *
   * This method is charset aware and uses Zips.charset.
   *
   * @param callback
   *          callback to be called for each entry.
   *
   * @see ZipInfoCallback
   * @see #iterate(ZipEntryCallback)
   */
  public void iterate(ZipInfoCallback callback) {
    ZipEntryOrInfoAdapter zipEntryAdapter = new ZipEntryOrInfoAdapter(null, callback);

    iterateChangedAndAdded(zipEntryAdapter);
    iterateExistingExceptRemoved(zipEntryAdapter);
  }

  // ///////////// private api ///////////////

  /**
   * Iterate through source for not removed entries with a given callback
   *
   * @param zipEntryCallback callback to execute on entries or their info.
   */
  private void iterateExistingExceptRemoved(ZipEntryOrInfoAdapter zipEntryCallback) {
    if (src == null) {
      // if we don't have source specified, then we have nothing to iterate.
      return;
    }
    final Set removedDirs = ZipUtil.filterDirEntries(src, removedEntries);

    ZipFile zf = null;
    try {
      zf = getZipFile();

      // manage existing entries
      Enumeration en = zf.entries();
      while (en.hasMoreElements()) {
        ZipEntry entry = (ZipEntry) en.nextElement();
        String entryName = entry.getName();
        if (removedEntries.contains(entryName) || isEntryInDir(removedDirs, entryName)) {
          // removed entries are
          continue;
        }

        if (nameMapper != null) {
          String mappedName = nameMapper.map(entry.getName());
          if (mappedName == null) {
            continue; // we should ignore this entry
          }
          else if (!mappedName.equals(entry.getName())) {
            // if name is different, do nothing
            entry = copy(entry, mappedName);
          }
        }

        InputStream is = zf.getInputStream(entry);
        try {
          zipEntryCallback.process(is, entry);
        }
        catch (ZipBreakException ex) {
          break;
        }
        finally {
          IOUtils.closeQuietly(is);
        }
      }
    }
    catch (IOException e) {
      throw ZipUtil.rethrow(e);
    }
    finally {
      ZipUtil.closeQuietly(zf);
    }
  }

  /**
   * Iterate through ZipEntrySources for added or changed entries with a given callback
   *
   * @param zipEntryCallback callback to execute on entries or their info
   */
  private void iterateChangedAndAdded(ZipEntryOrInfoAdapter zipEntryCallback) {
    for (Iterator it = changedEntries.iterator(); it.hasNext();) {
      ZipEntrySource entrySource = (ZipEntrySource) it.next();
      try {
        ZipEntry entry = entrySource.getEntry();
        if (nameMapper != null) {
          String mappedName = nameMapper.map(entry.getName());
          if (mappedName == null) {
            continue; // we should ignore this entry
          }
          else if (!mappedName.equals(entry.getName())) {
            // if name is different, do nothing
            entry = copy(entry, mappedName);
          }
        }
        zipEntryCallback.process(entrySource.getInputStream(), entry);
      }
      catch (ZipBreakException ex) {
        break;
      }
      catch (IOException e) {
        throw ZipUtil.rethrow(e);
      }
    }
  }

  /**
   * if we are doing something in place, move result file into src.
   *
   * @param result destination zip file
   */
  private void handleInPlaceActions(File result) throws IOException {
    if (isInPlace()) {
      // we operate in-place
      FileUtils.forceDelete(src);
      FileUtils.moveFile(result, src);
    }
  }

  /**
   * Checks if entry given by name resides inside of one of the dirs.
   *
   * @param dirNames dirs
   * @param entryName entryPath
   */
  private boolean isEntryInDir(Set dirNames, String entryName) {
    // this should be done with a trie, put dirNames in a trie and check if entryName leads to
    // some node or not.
    Iterator iter = dirNames.iterator();
    while (iter.hasNext()) {
      String dirName = (String) iter.next();
      if (entryName.startsWith(dirName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return transformers as array. Replace with .toArray, when we accept generics
   */
  private ZipEntryTransformerEntry[] getTransformersArray() {
    ZipEntryTransformerEntry[] result = new ZipEntryTransformerEntry[transformers.size()];
    int idx = 0;
    Iterator iter = transformers.iterator();
    while (iter.hasNext()) {
      result[idx++] = (ZipEntryTransformerEntry) iter.next();
    }
    return result;
  }

  /**
   * Copies a given ZIP entry to a ZIP file. If this.preserveTimestamps is true, original timestamp
   * is carried over, otherwise uses current time.
   *
   * @param zipEntry
   *          a ZIP entry from existing ZIP file.
   * @param in
   *          contents of the ZIP entry.
   * @param out
   *          target ZIP stream.
   */
  private void copyEntry(ZipEntry zipEntry, InputStream in, ZipOutputStream out) throws IOException {
    ZipEntry copy = copy(zipEntry);
    copy.setTime(preserveTimestamps ? zipEntry.getTime() : System.currentTimeMillis());
    ZipUtil.addEntry(copy, new BufferedInputStream(in), out);
  }

  /**
   * Copy entry
   *
   * @param original - zipEntry to copy
   * @return copy of the original entry
   */
  private ZipEntry copy(ZipEntry original) {
    return copy(original, null);
  }

  /**
   * Copy entry with another name.
   *
   * @param original - zipEntry to copy
   * @param newName - new entry name, optional, if null, ogirinal's entry
   * @return copy of the original entry, but with the given name
   */
  private ZipEntry copy(ZipEntry original, String newName) {
    ZipEntry copy = new ZipEntry(newName == null ? original.getName() : newName);
    if (original.getCrc() != -1) {
      copy.setCrc(original.getCrc());
    }
    if (original.getMethod() != -1) {
      copy.setMethod(original.getMethod());
    }
    if (original.getSize() >= 0) {
      copy.setSize(original.getSize());
    }
    if (original.getExtra() != null) {
      copy.setExtra(original.getExtra());
    }

    copy.setComment(original.getComment());
    copy.setCompressedSize(original.getCompressedSize());
    copy.setTime(original.getTime());
    return copy;
  }

  /**
   * Creates a ZipFile from src and charset of this object. If a constructor with charset is
   * not available, throws an exception.
   *
   * @return ZipFile
   * @throws IOException if ZipFile cannot be constructed
   * @throws IllegalArgumentException if accessing constructor ZipFile(File, Charset)
   *
   */
  private ZipFile getZipFile() throws IOException {
    return getZipFile(src, charset);
  }

  static ZipFile getZipFile(File src, Charset charset) throws IOException {
    if (charset == null) {
      return new ZipFile(src);
    }

    try {
      Constructor constructor = ZipFile.class.getConstructor(new Class[] { File.class, Charset.class });
      return (ZipFile) constructor.newInstance(new Object[] { src, charset });
    }
    catch (NoSuchMethodException e) {
      throw new IllegalStateException("Using constructor ZipFile(File, Charset) has failed: " + e.getMessage());
    }
    catch (InstantiationException e) {
      throw new IllegalStateException("Using constructor ZipFile(File, Charset) has failed: " + e.getMessage());
    }
    catch (IllegalAccessException e) {
      throw new IllegalStateException("Using constructor ZipFile(File, Charset) has failed: " + e.getMessage());
    }
    catch (IllegalArgumentException e) {
      throw new IllegalStateException("Using constructor ZipFile(File, Charset) has failed: " + e.getMessage());
    }
    catch (InvocationTargetException e) {
      throw new IllegalStateException("Using constructor ZipFile(File, Charset) has failed: " + e.getMessage());
    }
  }

  private ZipOutputStream createZipOutputStream(BufferedOutputStream outStream) {
    if (charset == null)
      return new ZipOutputStream(outStream);

    try {
      Constructor constructor = ZipOutputStream.class.getConstructor(new Class[] { OutputStream.class, Charset.class });
      return (ZipOutputStream) constructor.newInstance(new Object[] { outStream, charset });
    }
    catch (NoSuchMethodException e) {
      throw new IllegalStateException("Using constructor ZipOutputStream(OutputStream, Charset) has failed: " + e.getMessage());
    }
    catch (InstantiationException e) {
      throw new IllegalStateException("Using constructor ZipOutputStream(OutputStream, Charset) has failed: " + e.getMessage());
    }
    catch (IllegalAccessException e) {
      throw new IllegalStateException("Using constructor ZipOutputStream(OutputStream, Charset) has failed: " + e.getMessage());
    }
    catch (IllegalArgumentException e) {
      throw new IllegalStateException("Using constructor ZipOutputStream(OutputStream, Charset) has failed: " + e.getMessage());
    }
    catch (InvocationTargetException e) {
      throw new IllegalStateException("Using constructor ZipOutputStream(OutputStream, Charset) has failed: " + e.getMessage());
    }
  }

  private final class CopyingCallback implements ZipEntryCallback {

    private final Map entryByPath;
    private final ZipOutputStream out;
    private final Set visitedNames;

    private CopyingCallback(ZipEntryTransformerEntry[] entries, ZipOutputStream out) {
      this.out = out;
      entryByPath = ZipUtil.byPath(entries);
      visitedNames = new HashSet();
    }

    public void process(InputStream in, ZipEntry zipEntry) throws IOException {
      String entryName = zipEntry.getName();

      if (visitedNames.contains(entryName)) {
        return;
      }
      visitedNames.add(entryName);

      ZipEntryTransformer transformer = (ZipEntryTransformer) entryByPath.remove(entryName);
      if (transformer == null) { // no transformer
        copyEntry(zipEntry, in, out);
      }
      else { // still transfom entry
        transformer.transform(in, zipEntry, out);
      }
    }
  }

  private class ZipEntryOrInfoAdapter implements ZipEntryCallback, ZipInfoCallback {

    private final ZipEntryCallback entryCallback;
    private final ZipInfoCallback infoCallback;

    public ZipEntryOrInfoAdapter(ZipEntryCallback entryCallback, ZipInfoCallback infoCallback) {
      if (entryCallback != null && infoCallback != null || entryCallback == null && infoCallback == null) {
        throw new IllegalArgumentException("Only one of ZipEntryCallback and ZipInfoCallback must be specified together");
      }
      this.entryCallback = entryCallback;
      this.infoCallback = infoCallback;
    }

    public void process(ZipEntry zipEntry) throws IOException {
      infoCallback.process(zipEntry);
    }

    public void process(InputStream in, ZipEntry zipEntry) throws IOException {
      if (entryCallback != null) {
        entryCallback.process(in, zipEntry);
      }
      else {
        process(zipEntry);
      }
    }

  }
}
