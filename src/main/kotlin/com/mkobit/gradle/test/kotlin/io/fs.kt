package com.mkobit.gradle.test.kotlin.io

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import java.time.Instant

@DslMarker
private annotation class FilesDsl

/**
 * No operation.
 */
private val NoOp: Any.() -> Unit = {}

/**
 * A constant to be used with the DSL methods for a file that means "use the existing content".
 */
public val Original: CharSequence = object : CharSequence {
  override val length: Int
    get() = throw UnsupportedOperationException("Cannot retrieve length from ${this::class.java.canonicalName}")

  override fun get(index: Int): Char {
    throw UnsupportedOperationException("Cannot call subSequence from ${this::class.java.canonicalName}")
  }

  override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
    throw UnsupportedOperationException("Cannot call subSequence from ${this::class.java.canonicalName}")
  }
}

/**
 * A context for a file.
 * @property path the location of this context
 */
@FilesDsl
public sealed class FileContext(val path: Path) {

  /**
   * The file's last modified time.
   * @see Files.getLastModifiedTime
   * @see Files.setLastModifiedTime
   */
  public var lastModifiedTime: Instant
    get() = Files.getLastModifiedTime(path).toInstant()
    set(value) {
      Files.setLastModifiedTime(path, FileTime.from(value))
    }

  /**
   * Whether this file is considered hidden.
   * @see Files.isHidden
   */
  public val isHidden: Boolean
    get() = Files.isHidden(path)

  /**
   * Represents a regular file.
   * @property path the path of the regular file
   */
  public class RegularFileContext(path: Path) : FileContext(path) {
    init {
      require(Files.isRegularFile(path)) { "Path $path must be a regular file" }
    }

    /**
     * The contents of the file.
     */
    public var content: ByteArray
      get() = Files.readAllBytes(path)
      set(value) {
        Files.write(path, value)
      }

    /**
     * Size of file in bytes.
     * @see Files.size
     */
    public val size: Long
      get() = Files.size(path)

    /**
     * Appends the provided [content] to the file.
     * @param content the content to append
     */
    public fun append(content: ByteArray) {
      Files.write(path, content, StandardOpenOption.APPEND)
    }

    /**
     * Appends the provided [content] to the file after encoding it using the provided [charset].
     * @param content the content to append
     * @param charset the character set to encode the sequence with
     */
    public fun append(content: CharSequence, charset: Charset = Charsets.UTF_8) {
      Files.write(path, content.toString().toByteArray(charset), StandardOpenOption.APPEND)
    }

    /**
     * Appends a [System.lineSeparator] to the file using the provided [charset].
     * @param charset the character set to encode the newline with
     */
    public fun appendNewline(charset: Charset = Charsets.UTF_8) {
      Files.write(path, System.lineSeparator().toByteArray(charset), StandardOpenOption.APPEND)
    }

    /**
     * Replace the text of a file by invoking [replacement] on each line and then writing the output.
     * @param charset the character set to read the file with.
     * @param replacement the replacement strategy to be applied to each line. Line numbering starts from 1.
     * If the original text should be retained then [Original] should be returned.
     */
    public fun replaceEachLine(
        charset: Charset = Charsets.UTF_8,
        replacement: (lineNumber: Int, text: String) -> CharSequence
    ) {
      val newLines = Files.readAllLines(path, charset)
          .mapIndexed { index, line ->
            val newLine = replacement(index + 1, line)
            when(newLine) {
              Original -> line
              else -> newLine
            }
          }
      Files.write(path, newLines)
    }
  }

  /**
   * Represents a directory.
   * @property path the path of the directory
   */
  public class DirectoryContext(path: Path) : FileContext(path) {
    init {
      require(Files.isDirectory(path)) { "Path $path is not a directory" }
    }

    /**
     * Produce a [RegularFileContext] instance with a [Path] resolved from this instance's [path].
     * The instance is provisioned based on the provided [fileAction].
     *
     * @param filename the filename to resolve in this instance's [path]
     * @param fileAction the action to take for the file
     * @param action the lambda that can provide additional setup of the file
     * @return a [RegularFileContext] for the resolved file
     * @throws NoSuchFileException if the [action] is [FileAction.Get] and the file is not a
     * regular file
     * @throws FileAlreadyExistsException if the [action] is a creation method
     * ([FileAction.MaybeCreate] or [FileAction.Create]) and a file already exists at the resolved
     * path
     */
    @Throws(NoSuchFileException::class, FileAlreadyExistsException::class)
    public fun file(
        filename: CharSequence,
        fileAction: FileAction = FileAction.MaybeCreate,
        action: RegularFileContext.() -> Unit = NoOp
    ): RegularFileContext {
      val filePath = path.resolve(filename.toString())

      return translateIoExceptions {
        when (fileAction) {
          is FileAction.Get -> {
            if (!Files.isRegularFile(filePath)) {
              throw NoSuchFileException(filePath.toFile(), reason = "Regular file does not exist at $filePath")
            }
            RegularFileContext(filePath)
          }
          is FileAction.MaybeCreate -> {
            if (Files.exists(filePath)) {
              if (!Files.isRegularFile(filePath)) {
                throw FileAlreadyExistsException(filePath.toFile(),
                    reason = "File at path $filePath already exists and is not a regular file")
              }
              RegularFileContext(filePath)
            } else {
              RegularFileContext(Files.createFile(filePath, *fileAction.fileAttributes.toTypedArray()))
            }
          }
          is FileAction.Create -> RegularFileContext(Files.createFile(
              filePath,
              *fileAction.fileAttributes.toTypedArray()))
        }.apply(action)
      }
    }

    /**
     * Produce a [DirectoryContext] instance with a [Path] resolved from this instance's [path].
     * The instance is provisioned based on the provided [fileAction].
     *
     * @param directoryName the directory name to resolve in this instance's [path]
     * @param fileAction the action to take for the file
     * @param action the lambda that can provide additional setup of the file
     * @return a [DirectoryContext] for the resolved directory
     * @throws NoSuchFileException if the [action] is [FileAction.Get] and the file is not a
     * directory
     * @throws FileAlreadyExistsException if the [action] is a creation method
     * ([FileAction.MaybeCreate] or [FileAction.Create]) and a file already exists at the resolved
     * path
     */
    @Throws(NoSuchFileException::class, FileAlreadyExistsException::class)
    public fun directory(
        directoryName: CharSequence,
        fileAction: FileAction = FileAction.MaybeCreate,
        action: DirectoryContext.() -> Unit = NoOp
    ): DirectoryContext {
      val filePath = path.resolve(directoryName.toString())
      return translateIoExceptions {
        when (fileAction) {
          is FileAction.Get -> {
            if (!Files.isDirectory(filePath)) {
              throw NoSuchFileException(filePath.toFile(), reason = "Directory does not exist at $filePath")
            }
            DirectoryContext(filePath)
          }
          is FileAction.MaybeCreate -> {
            if (Files.exists(filePath)) {
              if (!Files.isDirectory(filePath)) {
                throw FileAlreadyExistsException(filePath.toFile(),
                    reason = "File at path $filePath already exists and is not a directory")
              }
              DirectoryContext(filePath)
            } else {
              DirectoryContext(Files.createDirectories(filePath,
                  *fileAction.fileAttributes.toTypedArray()))
            }
          }
          is FileAction.Create -> {
            if (Files.exists(filePath)) {
              throw FileAlreadyExistsException(filePath.toFile(), reason = "File at path $filePath already exists")
            }
            DirectoryContext(Files.createDirectories(filePath, *fileAction.fileAttributes.toTypedArray()))
          }
        }.apply(action)
      }
    }

    /**
     * Produce a [DirectoryContext] instance with a [Path] resolved from this instance's [path] and the [CharSequence]
     * that this method was invoked on.
     * The instance is provisioned based on the provided [fileAction].
     *
     * @param fileAction the action to take for the file
     * @param action the lambda that can provide additional setup of the file
     * @return a [DirectoryContext] for the resolved directory
     * @throws NoSuchFileException if the [action] is [FileAction.Get] and the file is not a
     * directory
     * @throws FileAlreadyExistsException if the [action] is a creation method
     * ([FileAction.MaybeCreate] or [FileAction.Create]) and a file already exists at the resolved
     * path
     * @see directory
     */
    @Throws(NoSuchFileException::class, FileAlreadyExistsException::class)
    public operator fun CharSequence.invoke(
        fileAction: FileAction = FileAction.MaybeCreate,
        action: DirectoryContext.() -> Unit
    ): DirectoryContext = directory(this, fileAction, action)

    /**
     * Produce a [RegularFileContext] instance with a [Path] resolved from this instance's [path] and the [CharSequence]
     * that this method was invoked on. The content of the file will be TODO
     * The instance is provisioned based on the provided [fileAction].
     *
     * @param fileAction the action to take for the file
     * @param content the optional content to write into the file. If `null`, then no content is written to the file.
     * @param encoding the encoding to use with the content, defaults to [Charsets.UTF_8]
     * @param action the lambda that can provide additional setup of the file
     * @return a [RegularFileContext] for the resolved file
     * @throws NoSuchFileException if the [action] is [FileAction.Get] and the file is not a
     * regular file
     * @throws FileAlreadyExistsException if the [action] is a creation method
     * ([FileAction.MaybeCreate] or [FileAction.Create]) and a file already exists at the resolved
     * path
     * @see file
     */
    @Throws(NoSuchFileException::class, FileAlreadyExistsException::class)
    public operator fun CharSequence.invoke(
        fileAction: FileAction = FileAction.MaybeCreate,
        content: CharSequence,
        encoding: Charset = Charsets.UTF_8,
        action: RegularFileContext.() -> Unit = NoOp
    ): RegularFileContext = file(this, fileAction) {
      if (content !== Original) {
        this.content = content.toString().toByteArray(encoding)
      }
    }.apply(action)
  }
}

/**
 * A representation of how a file request should be handled.
 */
public sealed class FileAction {
  /**
   * Get the request file object.
   */
  public object Get : FileAction()

  public companion object {
    /**
     * Get the file object if it already exists, otherwise create it with no specified options.
     */
    public val MaybeCreate = MaybeCreate(emptyList())

    /**
     * Create the file with no specified options.
     */
    public val Create = Create(emptyList())
  }

  /**
   * Get the object if it already exists, otherwise create it with the provided options.
   * @property fileAttributes the attributes to create the file with if the file has not already been created
   */
  public data class MaybeCreate(val fileAttributes: List<FileAttribute<*>>) : FileAction()

  /**
   * Create the file with the provided properties.
   * @property fileAttributes the file attributes to create the file with
   */
  public data class Create(val fileAttributes: List<FileAttribute<*>>) : FileAction()
}

/**
 * Wraps the call and translates the exception.
 * @throws NoSuchFileException when [java.nio.file.NoSuchFileException] is thrown
 * @throws FileAlreadyExistsException when [java.nio.file.FileAlreadyExistsException] is thrown
 */
@Throws(NoSuchFileException::class, FileAlreadyExistsException::class)
private fun <T> translateIoExceptions(supplier: () -> T): T = try {
  supplier()
} catch (noSuchFile: java.nio.file.NoSuchFileException) {
  throw NoSuchFileException(File(noSuchFile.file)).initCause(noSuchFile)
} catch (alreadyExists: java.nio.file.FileAlreadyExistsException) {
  throw FileAlreadyExistsException(File(alreadyExists.file)).initCause(alreadyExists)
}
