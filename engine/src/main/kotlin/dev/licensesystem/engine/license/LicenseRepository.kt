package dev.licensesystem.engine.license

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class LicenseRepository(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    init {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table if not exists licenses (
                        license_key varchar(64) primary key,
                        product_id varchar(128) not null,
                        owner_name varchar(128) not null,
                        status varchar(16) not null,
                        created_at varchar(40) not null,
                        expires_at varchar(40),
                        max_servers int not null,
                        notes clob,
                        last_validated_at varchar(40),
                        last_plugin_version varchar(64),
                        last_minecraft_version varchar(64)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    create table if not exists license_activations (
                        license_key varchar(64) not null,
                        fingerprint varchar(128) not null,
                        server_name varchar(255) not null,
                        first_seen_at varchar(40) not null,
                        last_seen_at varchar(40) not null,
                        primary key (license_key, fingerprint),
                        constraint fk_license_activations_license
                            foreign key (license_key) references licenses(license_key)
                            on delete cascade
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("create index if not exists idx_licenses_product_id on licenses(product_id)")
            }
        }
    }

    fun save(record: LicenseRecord): LicenseRecord = withTransaction { connection ->
        connection.prepareStatement(
            """
            merge into licenses (
                license_key,
                product_id,
                owner_name,
                status,
                created_at,
                expires_at,
                max_servers,
                notes,
                last_validated_at,
                last_plugin_version,
                last_minecraft_version
            ) key(license_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, record.key)
            statement.setString(2, record.productId)
            statement.setString(3, record.owner)
            statement.setString(4, record.status.name)
            statement.setString(5, record.createdAt)
            statement.setString(6, record.expiresAt)
            statement.setInt(7, record.maxServers)
            statement.setString(8, record.notes)
            statement.setString(9, record.lastValidatedAt)
            statement.setString(10, record.lastPluginVersion)
            statement.setString(11, record.lastMinecraftVersion)
            statement.executeUpdate()
        }

        connection.prepareStatement("delete from license_activations where license_key = ?").use { statement ->
            statement.setString(1, record.key)
            statement.executeUpdate()
        }

        if (record.activations.isNotEmpty()) {
            connection.prepareStatement(
                """
                insert into license_activations (
                    license_key,
                    fingerprint,
                    server_name,
                    first_seen_at,
                    last_seen_at
                ) values (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                record.activations.forEach { activation ->
                    statement.setString(1, record.key)
                    statement.setString(2, activation.fingerprint)
                    statement.setString(3, activation.serverName)
                    statement.setString(4, activation.firstSeenAt)
                    statement.setString(5, activation.lastSeenAt)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        record
    }

    fun find(key: String): LicenseRecord? = withConnection { connection ->
        connection.prepareStatement(
            """
            select
                license_key,
                product_id,
                owner_name,
                status,
                created_at,
                expires_at,
                max_servers,
                notes,
                last_validated_at,
                last_plugin_version,
                last_minecraft_version
            from licenses
            where license_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection null
                }

                mapRecord(resultSet, loadActivations(connection, listOf(key))[key].orEmpty())
            }
        }
    }

    fun list(productId: String? = null): List<LicenseRecord> = withConnection { connection ->
        val records = mutableListOf<PartialLicenseRecord>()
        val sql = if (productId.isNullOrBlank()) {
            """
            select
                license_key,
                product_id,
                owner_name,
                status,
                created_at,
                expires_at,
                max_servers,
                notes,
                last_validated_at,
                last_plugin_version,
                last_minecraft_version
            from licenses
            order by created_at desc
            """.trimIndent()
        } else {
            """
            select
                license_key,
                product_id,
                owner_name,
                status,
                created_at,
                expires_at,
                max_servers,
                notes,
                last_validated_at,
                last_plugin_version,
                last_minecraft_version
            from licenses
            where lower(product_id) = lower(?)
            order by created_at desc
            """.trimIndent()
        }

        connection.prepareStatement(sql).use { statement ->
            if (!productId.isNullOrBlank()) {
                statement.setString(1, productId)
            }

            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    records += mapPartialRecord(resultSet)
                }
            }
        }

        val activations = loadActivations(connection, records.map { it.key })
        records.map { it.toLicenseRecord(activations[it.key].orEmpty()) }
    }

    private fun loadActivations(
        connection: Connection,
        licenseKeys: List<String>
    ): Map<String, List<ServerActivation>> {
        if (licenseKeys.isEmpty()) {
            return emptyMap()
        }

        val placeholders = licenseKeys.joinToString(",") { "?" }
        val sql = """
            select license_key, fingerprint, server_name, first_seen_at, last_seen_at
            from license_activations
            where license_key in ($placeholders)
            order by first_seen_at asc
        """.trimIndent()

        connection.prepareStatement(sql).use { statement ->
            licenseKeys.forEachIndexed { index, licenseKey ->
                statement.setString(index + 1, licenseKey)
            }

            return statement.executeQuery().use { resultSet ->
                val result = linkedMapOf<String, MutableList<ServerActivation>>()
                while (resultSet.next()) {
                    val licenseKey = resultSet.getString("license_key")
                    result.getOrPut(licenseKey) { mutableListOf() }
                        .add(
                            ServerActivation(
                                fingerprint = resultSet.getString("fingerprint"),
                                serverName = resultSet.getString("server_name"),
                                firstSeenAt = resultSet.getString("first_seen_at"),
                                lastSeenAt = resultSet.getString("last_seen_at")
                            )
                        )
                }
                result
            }
        }
    }

    private fun mapRecord(resultSet: ResultSet, activations: List<ServerActivation>): LicenseRecord {
        return mapPartialRecord(resultSet).toLicenseRecord(activations)
    }

    private fun mapPartialRecord(resultSet: ResultSet): PartialLicenseRecord {
        return PartialLicenseRecord(
            key = resultSet.getString("license_key"),
            productId = resultSet.getString("product_id"),
            owner = resultSet.getString("owner_name"),
            status = LicenseStatus.valueOf(resultSet.getString("status")),
            createdAt = resultSet.getString("created_at"),
            expiresAt = resultSet.getString("expires_at"),
            maxServers = resultSet.getInt("max_servers"),
            notes = resultSet.getString("notes"),
            lastValidatedAt = resultSet.getString("last_validated_at"),
            lastPluginVersion = resultSet.getString("last_plugin_version"),
            lastMinecraftVersion = resultSet.getString("last_minecraft_version")
        )
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl, username, password).use(action)
    }

    private fun <T> withTransaction(action: (Connection) -> T): T {
        return withConnection { connection ->
            connection.autoCommit = false
            try {
                val result = action(connection)
                connection.commit()
                result
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private data class PartialLicenseRecord(
        val key: String,
        val productId: String,
        val owner: String,
        val status: LicenseStatus,
        val createdAt: String,
        val expiresAt: String?,
        val maxServers: Int,
        val notes: String?,
        val lastValidatedAt: String?,
        val lastPluginVersion: String?,
        val lastMinecraftVersion: String?
    ) {
        fun toLicenseRecord(activations: List<ServerActivation>): LicenseRecord {
            return LicenseRecord(
                key = key,
                productId = productId,
                owner = owner,
                status = status,
                createdAt = createdAt,
                expiresAt = expiresAt,
                maxServers = maxServers,
                notes = notes,
                activations = activations,
                lastValidatedAt = lastValidatedAt,
                lastPluginVersion = lastPluginVersion,
                lastMinecraftVersion = lastMinecraftVersion
            )
        }
    }
}
