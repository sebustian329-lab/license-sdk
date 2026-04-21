package dev.licensesystem.engine.product

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class ProductRepository(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    init {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table if not exists products (
                        product_key varchar(255) primary key,
                        public_token varchar(64) not null,
                        created_at varchar(40) not null
                    )
                    """.trimIndent()
                )
                runCatching {
                    statement.executeUpdate("alter table products alter column product_key varchar(255)")
                }
            }
        }
    }

    fun create(productKey: String, publicToken: String): ProductRecord {
        val record = ProductRecord(
            productKey = productKey,
            publicToken = publicToken,
            createdAt = Instant.now().toString()
        )

        withConnection { connection ->
            connection.prepareStatement(
                "insert into products (product_key, public_token, created_at) values (?, ?, ?)"
            ).use { statement ->
                statement.setString(1, record.productKey)
                statement.setString(2, record.publicToken)
                statement.setString(3, record.createdAt)
                statement.executeUpdate()
            }
        }

        return record
    }

    fun find(productKey: String): ProductRecord? = withConnection { connection ->
        connection.prepareStatement(
            "select product_key, public_token, created_at from products where product_key = ?"
        ).use { statement ->
            statement.setString(1, productKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection null
                }

                ProductRecord(
                    productKey = resultSet.getString("product_key"),
                    publicToken = resultSet.getString("public_token"),
                    createdAt = resultSet.getString("created_at")
                )
            }
        }
    }

    fun list(): List<ProductRecord> = withConnection { connection ->
        connection.prepareStatement(
            "select product_key, public_token, created_at from products order by created_at desc"
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                val result = mutableListOf<ProductRecord>()
                while (resultSet.next()) {
                    result += ProductRecord(
                        productKey = resultSet.getString("product_key"),
                        publicToken = resultSet.getString("public_token"),
                        createdAt = resultSet.getString("created_at")
                    )
                }
                result
            }
        }
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl, username, password).use(action)
    }
}
