package org.intellij.prisma.completion

import org.intellij.prisma.lang.PrismaConstants.BlockAttributes

class PrismaBlockAttributesCompletionTest : PrismaCompletionTestBase("completion/blockAttributes") {
  fun testBlockAttributes() {
    val lookupElements = completeSelected(
      """
          datasource db {
            provider = "mysql"
          }
          model M {
            <caret>
          }
      """.trimIndent(), """
          datasource db {
            provider = "mysql"
          }
          model M {
            @@id([<caret>])
          }
      """.trimIndent(),
      "@@id"
    )
    assertSameElements(lookupElements.strings, BlockAttributes.ALL - BlockAttributes.SCHEMA)
    checkLookupDocumentation(lookupElements, "@@id")
  }

  fun testBlockAttributesPrefixAt() {
    val lookupElements = completeSelected(
      """
          model M {
            @<caret>
          }
      """.trimIndent(), """
          model M {
            @@map("<caret>")
          }
      """.trimIndent(),
      "@@map"
    )
    assertSameElements(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesPrefixAtAfterField() {
    val lookupElements = completeSelected(
      """
          model M {
            id String
            
            @<caret>
          }
      """.trimIndent(), """
          model M {
            id String
            
            @@index([<caret>])
          }
      """.trimIndent(),
      "@@index"
    )
    assertSameElements(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesPrefixAtAt() {
    val lookupElements = completeSelected(
      """
          model M {
            @@<caret>
          }
      """.trimIndent(), """
          model M {
            @@unique([<caret>])
          }
      """.trimIndent(),
      "@@unique"
    )
    assertSameElements(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesPrefixAtAtAfterField() {
    val lookupElements = completeSelected(
      """
          model M {
            id String
            
            @@<caret>
          }
      """.trimIndent(), """
          model M {
            id String
            
            @@unique([<caret>])
          }
      """.trimIndent(),
      "@@unique"
    )
    assertSameElements(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesPrefixName() {
    completeBasic(
      """
          model M {
            @@ign<caret>
          }
      """.trimIndent(), """
          model M {
            @@ignore<caret>
          }
      """.trimIndent()
    )
  }

  fun testBlockAttributesFulltext() {
    completeBasic(
      """
          datasource db {
            provider = "mysql"
          }
          
          model M {
            @@full<caret>
          }
      """.trimIndent(), """
          datasource db {
            provider = "mysql"
          }
          
          model M {
            @@fulltext([<caret>])
          }
      """.trimIndent()
    )
  }

  fun testBlockAttributesNoFulltext() {
    val lookupElements = getLookupElements(
      """
          datasource db {
            provider = "postgresql"
          }
          
          model M {
            @@<caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.FULLTEXT)
  }

  fun testNoBlockAttributesInCompositeType() {
    val lookupElements = getLookupElements(
      """
          type M {
            <caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testNoBlockAttributesForField() {
    val lookupElements = getLookupElements(
      """
          model M {
            id String <caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesNoIdIfHasIdFieldAttribute() {
    val lookupElements = getLookupElements(
      """
          model M {
            id Int @id
            
            <caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.ID)
  }

  fun testBlockAttributesNoDuplicates() {
    val lookupElements = getLookupElements(
      """
          model M {
            id Int
            
            @@map("foo")
            @@unique([id])
            <caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.MAP, BlockAttributes.UNIQUE)
  }

  fun testNoBlockAttributesForFieldAfterAt() {
    val lookupElements = getLookupElements(
      """
          model M {
            id String @<caret>
          }
      """.trimIndent()
    )
    assertDoesntContain(lookupElements.strings, BlockAttributes.ALL)
  }

  fun testBlockAttributesEnum() {
    val lookupElements = getLookupElements(
      """
          enum Role {
            ADMIN
            USER
            GUEST
            
            @<caret>
          }
      """.trimIndent()
    )
    assertSameElements(lookupElements.strings, BlockAttributes.MAP)
  }

  fun testBlockAttributesEnumWithoutAt() {
    val lookupElements = getLookupElements(
      """
          enum Role {
            ADMIN
            USER
            GUEST
            
            <caret>
          }
      """.trimIndent()
    )
    assertSameElements(lookupElements.strings, BlockAttributes.MAP)
  }

  fun testBlockAttributesEnumAtAt() {
    val lookupElements = getLookupElements(
      """
          enum Role {
            ADMIN
            USER
            GUEST
            
            @@<caret>
          }
      """.trimIndent()
    )
    assertSameElements(lookupElements.strings, BlockAttributes.MAP)
  }

  fun testSchemaAttribute() {
    val lookupElements = completeSelected(
      """
        generator client {
          provider        = "prisma-client-js"
          previewFeatures = ["multiSchema"]
        }

        datasource db {
          provider = "postgresql"
          url      = ""
          schemas  = ["base-schema", "login"]
        }

        model User {
          id Int @id

          @@schema("<caret>")
        }
      """.trimIndent(),
      """
        generator client {
          provider        = "prisma-client-js"
          previewFeatures = ["multiSchema"]
        }

        datasource db {
          provider = "postgresql"
          url      = ""
          schemas  = ["base-schema", "login"]
        }

        model User {
          id Int @id

          @@schema("base-schema")
        }
      """.trimIndent(),
      "base-schema",
    )
    assertSameElements(lookupElements.strings, "base-schema", "login")
  }

  fun testSchemaAttributeQuoteHandler() {
    val lookupElements = completeSelected(
      """
        generator client {
          provider        = "prisma-client-js"
          previewFeatures = ["multiSchema"]
        }

        datasource db {
          provider = "postgresql"
          url      = ""
          schemas  = ["base-schema", "login"]
        }

        model User {
          id Int @id

          @@schema(<caret>)
        }
      """.trimIndent(),
      """
        generator client {
          provider        = "prisma-client-js"
          previewFeatures = ["multiSchema"]
        }

        datasource db {
          provider = "postgresql"
          url      = ""
          schemas  = ["base-schema", "login"]
        }

        model User {
          id Int @id

          @@schema("base-schema")
        }
      """.trimIndent(),
      "\"base-schema\"",
    )
    assertSameElements(lookupElements.strings, "\"base-schema\"", "\"login\"")
  }

  fun testBlockAttributesShardKey() {
    completeSelected("""
      generator client {
        provider        = "prisma-client-js"
        previewFeatures = ["shardKeys"]
      }

      datasource db {
        provider = "mysql"
        url      = "file:./dev.db"
      }

      model User {
        id    Int     @id @default(autoincrement())
        email String  @unique
        name  String?
        
        @@<caret>
      }
    """.trimIndent(), """
      generator client {
        provider        = "prisma-client-js"
        previewFeatures = ["shardKeys"]
      }

      datasource db {
        provider = "mysql"
        url      = "file:./dev.db"
      }

      model User {
        id    Int     @id @default(autoincrement())
        email String  @unique
        name  String?
        
        @@shardKey([<caret>])
      }
    """.trimIndent(), "@@shardKey")
  }

  fun testBlockAttributeShardKeyOnlyMysql() {
    val lookupElements = getLookupElements("""
      generator client {
        provider        = "prisma-client-js"
        previewFeatures = ["shardKeys"]
      }

      datasource db {
        provider = "postgresql"
        url      = "file:./dev.db"
      }

      model User {
        id    Int     @id @default(autoincrement())
        email String  @unique
        name  String?
        
        @@<caret>
      }
    """.trimIndent())
    assertDoesntContain(lookupElements.strings, BlockAttributes.SHARD_KEY)
  }
}