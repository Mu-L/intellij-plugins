package org.intellij.prisma.ide.schema.definitions

import com.intellij.patterns.PlatformPatterns.psiElement
import org.intellij.prisma.ide.completion.PrismaInsertHandler
import org.intellij.prisma.ide.schema.PrismaSchemaKind
import org.intellij.prisma.ide.schema.builder.PrismaSchemaParameterLocation
import org.intellij.prisma.ide.schema.builder.schema
import org.intellij.prisma.ide.schema.types.PrismaDatasourceProviderType.*
import org.intellij.prisma.lang.PrismaConstants.BlockAttributes
import org.intellij.prisma.lang.PrismaConstants.Functions
import org.intellij.prisma.lang.PrismaConstants.ParameterNames
import org.intellij.prisma.lang.PrismaConstants.Types
import org.intellij.prisma.lang.psi.PrismaEnumDeclaration
import org.intellij.prisma.lang.psi.PrismaModelDeclaration
import org.intellij.prisma.lang.psi.PrismaPsiPatterns
import java.util.*

val PRISMA_SCHEMA_BLOCK_ATTRIBUTES = schema {
  group(PrismaSchemaKind.BLOCK_ATTRIBUTE) {
    element {
      label = BlockAttributes.MAP
      insertHandler = PrismaInsertHandler.PARENS_QUOTED_ARGUMENT
      documentation = "Maps a model name from the Prisma schema to a different table name."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(
        psiElement().andOr(psiElement(PrismaModelDeclaration::class.java), psiElement(PrismaEnumDeclaration::class.java)))

      param {
        label = ParameterNames.NAME
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "The name of the target database table."
        type = "String"
      }
    }

    element {
      label = BlockAttributes.ID
      insertHandler = PrismaInsertHandler.PARENS_LIST_ARGUMENT
      documentation = "Defines a multi-field ID on the model."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))

      param {
        label = ParameterNames.FIELDS
        insertHandler = PrismaInsertHandler.COLON_LIST_ARGUMENT
        documentation = "A list of references."
        type = "FieldReference[]"
      }
      param {
        label = ParameterNames.NAME
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines the name in your Prisma Client API."
        type = "String?"
      }
      param {
        label = ParameterNames.MAP
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines a custom name for the primary key in the database."
        type = "String?"
      }
      length(PrismaSchemaParameterLocation.FIELD)
      sort(PrismaSchemaParameterLocation.FIELD, datasourceTypes = EnumSet.of(SQLSERVER))
      clustered()
    }

    element {
      label = BlockAttributes.UNIQUE
      insertHandler = PrismaInsertHandler.PARENS_LIST_ARGUMENT
      documentation = "Defines a compound unique constraint for the specified fields."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))

      param {
        label = ParameterNames.FIELDS
        insertHandler = PrismaInsertHandler.COLON_LIST_ARGUMENT
        documentation = "A list of references."
        type = "FieldReference[]"
      }
      param {
        label = ParameterNames.NAME
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines the name in your Prisma Client API."
        type = "String?"
      }
      param {
        label = ParameterNames.MAP
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines a custom constraint name in the database."
        type = "String?"
      }
      length(PrismaSchemaParameterLocation.FIELD)
      sort(PrismaSchemaParameterLocation.FIELD)
      clustered()
    }

    element {
      label = BlockAttributes.INDEX
      insertHandler = PrismaInsertHandler.PARENS_LIST_ARGUMENT
      documentation = "Defines an index on the model."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))

      param {
        label = ParameterNames.FIELDS
        insertHandler = PrismaInsertHandler.COLON_LIST_ARGUMENT
        documentation = "A list of references."
        type = "FieldReference[]"
      }
      param {
        label = ParameterNames.MAP
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines a custom index name in the database."
        type = "String?"
      }
      param {
        label = ParameterNames.TYPE
        documentation = "Defines the access type of indexes: BTree (default) or Hash."
        type = Types.INDEX_TYPE.optional()
        datasources = EnumSet.of(POSTGRESQL)

        variantsForType(Types.INDEX_TYPE)
      }
      param {
        label = ParameterNames.OPS
        documentation = "Specify the operator class for an indexed field."
        type = Types.OPERATOR_CLASS.optional()
        datasources = EnumSet.of(POSTGRESQL)
        location = PrismaSchemaParameterLocation.FIELD

        variant {
          ref {
            kind = PrismaSchemaKind.FUNCTION
            label = Functions.RAW
          }
        }
      }
      length(PrismaSchemaParameterLocation.FIELD)
      sort(PrismaSchemaParameterLocation.FIELD)
      clustered()
    }

    element {
      label = BlockAttributes.FULLTEXT
      insertHandler = PrismaInsertHandler.PARENS_LIST_ARGUMENT
      documentation = "Defines a full-text index on the model."
      datasources = EnumSet.of(MYSQL, MONGODB)
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))

      param {
        label = ParameterNames.FIELDS
        insertHandler = PrismaInsertHandler.COLON_LIST_ARGUMENT
        documentation = "A list of references."
        type = "FieldReference[]"
      }
      param {
        label = ParameterNames.MAP
        insertHandler = PrismaInsertHandler.COLON_QUOTED_ARGUMENT
        documentation = "Defines a custom index name in the database."
        type = "String?"
      }
    }

    element {
      label = BlockAttributes.IGNORE
      documentation =
        "A model with an `@@ignore` attribute can be kept in sync with the database schema using Prisma Migrate and Introspection, but won't be exposed in Prisma Client."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))
    }

    element {
      label = BlockAttributes.SCHEMA
      insertHandler = PrismaInsertHandler.PARENS_QUOTED_ARGUMENT
      documentation = "Designate which schema this belongs to. [Learn more](https://pris.ly/d/multi-schema-configuration)"
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))
      datasources = EnumSet.of(POSTGRESQL, COCKROACHDB, SQLSERVER)

      param {
        label = ParameterNames.NAME
        documentation = "The name of the schema."
        type = "String"
        skipInCompletion = true

        variant {
          ref { kind = PrismaSchemaKind.SCHEMA_NAME }
        }
      }
    }

    element {
      label = BlockAttributes.SHARD_KEY
      documentation = "The `@@shardKey` attribute is only compatible with [PlanetScale](https://planetscale.com/) databases. It enables you to define a [shard key](https://planetscale.com/docs/vitess/sharding) on multiple fields of your model."
      pattern = PrismaPsiPatterns.insideEntityDeclaration(psiElement(PrismaModelDeclaration::class.java))
      insertHandler = PrismaInsertHandler.PARENS_LIST_ARGUMENT
      datasources = EnumSet.of(MYSQL)

      param {
        label = ParameterNames.FIELDS
        insertHandler = PrismaInsertHandler.COLON_LIST_ARGUMENT
        documentation = "A list of references."
        type = "FieldReference[]"
        skipInCompletion = true
      }
    }
  }
}
