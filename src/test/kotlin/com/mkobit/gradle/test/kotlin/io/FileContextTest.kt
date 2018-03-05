package com.mkobit.gradle.test.kotlin.io

import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.extension.ExtendWith
import testsupport.TempDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

@ExtendWith(TempDirectory::class)
internal class FileContextTest {

  @Nested
  inner class DirectoryContextTest {

    private lateinit var directoryContext: FileContext.DirectoryContext

    @BeforeEach
    internal fun setUp(@TempDirectory.Root root: Path, testInfo: TestInfo) {
      directoryContext = FileContext.DirectoryContext(Files.createDirectories(root.resolve(testInfo.displayName)))
    }

    @Nested
    inner class FileActionMaybeCreate {
      private val requestType = FileAction.MaybeCreate

      @TestFactory
      internal fun test(): Stream<DynamicNode> {
        return Stream.of(
            dynamicTest("by explicitly passing in a ${FileAction::class.simpleName} of ${requestType::class.simpleName}") {
              directoryContext.run {
                Files.createFile(directoryContext.path.resolve("filename1"))
                assertThatExceptionOfType(FileAlreadyExistsException::class.java)
                    .isThrownBy {
                      "filename1"(requestType) {}
                    }
              }
            },
            dynamicTest("using default parameter value of ${FileAction::class.simpleName}") {
              directoryContext.run {
                Files.createFile(directoryContext.path.resolve("filename2"))
                assertThatExceptionOfType(FileAlreadyExistsException::class.java)
                    .isThrownBy {
                      "filename2" {}
                    }
              }
            }
        )
      }
    }
  }
}
