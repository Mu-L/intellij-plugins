package org.intellij.prisma.completion

class PrismaKeyValueCompletionTest : PrismaCompletionTestBase("completion/keyValue") {
  fun testDatasourceField() {
    val lookupElements = completeSelected(
      """
          datasource db {
            <caret>
          }
      """.trimIndent(), """
          datasource db {
            url = <caret>
          }
      """.trimIndent(),
      "url"
    )
    assertSameElements(lookupElements.strings, "directUrl", "provider", "url", "shadowDatabaseUrl", "relationMode", "extensions", "schemas")
    checkLookupDocumentation(lookupElements, "url")
  }

  fun testDatasourceNoRelationModeForMongo() {
    val lookupElements = getLookupElements(
      """
          datasource db {
            provider = "mongodb"
            <caret>
          }
      """.trimIndent()
    )
    assertSameElements(lookupElements.strings, "directUrl", "url", "shadowDatabaseUrl")
  }

  fun testDatasourceExtensions() {
    completeSelected(
      """
          datasource db {
            provider   = "postgresql"
            url        = env("DATABASE_URL")
            <caret>
          }
      """.trimIndent(), """
          datasource db {
            provider   = "postgresql"
            url        = env("DATABASE_URL")
            extensions = [<caret>]
          }
      """.trimIndent(),
      "extensions"
    )
  }

  fun testGeneratorField() {
    val lookupElements = completeSelected(
      """
          generator client {
            <caret>
          }
      """.trimIndent(), """
          generator client {
            provider = "<caret>"
          }
      """.trimIndent(),
      "provider"
    )
    assertSameElements(
      lookupElements.strings,
      "provider",
      "output",
      "binaryTargets",
      "previewFeatures",
      "engineType",
    )
    checkLookupDocumentation(lookupElements, "provider")
  }

  fun testGeneratorPreviewFeatures() {
    val lookupElements = completeSelected(
      """
          generator client {
            provider = "prisma-client-js"
            <caret>
            engineType = "binary"
          }
      """.trimIndent(), """
          generator client {
            provider = "prisma-client-js"
            previewFeatures = [<caret>]
            engineType = "binary"
          }
      """.trimIndent(),
      "previewFeatures"
    )
    assertSameElements(lookupElements.strings, "output", "binaryTargets", "previewFeatures")
  }

  fun testGeneratorBinaryTargets() {
    completeSelected(
      """
          generator client {
            <caret>
          }
      """.trimIndent(), """
          generator client {
            binaryTargets = [<caret>]
          }
      """.trimIndent(),
      "binaryTargets"
    )
  }

  fun testGeneratorWithPrismaClientProvider() {
    val lookupElements = getLookupElements(
      """
          generator client {
            provider = "prisma-client"
            <caret>
          }
      """.trimIndent()
    )
    assertContainsElements(
      lookupElements.strings,
      "runtime",
      "moduleFormat",
      "generatedFileExtension",
      "importFileExtension"
    )
  }

  fun testGeneratorWithDefaultProvider() {
    val lookupElements = getLookupElements(
      """
          generator client {
            provider = "prisma-client-js"
            <caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(
      lookupElements.strings,
      "runtime",
      "moduleFormat",
      "generatedFileExtension",
      "importFileExtension"
    )
  }
}
