package com.tw93.miaoyan.android.git

import java.util.Base64

internal object GitConflictCodec {
    fun encode(details: GitConflictDetails): String = buildString {
        append(details.localCommit).append('\t').append(details.remoteCommit).append('\n')
        details.files.forEach { file ->
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(file.path.toByteArray(Charsets.UTF_8)))
            append('\t').append(file.localModifiedAtMillis ?: "-")
            append('\t').append(file.remoteModifiedAtMillis ?: "-")
            append('\t').append(if (file.localExists) '1' else '0')
            append('\t').append(if (file.remoteExists) '1' else '0').append('\n')
        }
    }

    fun decode(value: String): GitConflictDetails? = runCatching {
        val lines = value.lineSequence().filter(String::isNotBlank).toList()
        val header = lines.first().split('\t')
        require(header.size == 2 && header.all { it.matches(Regex("[0-9a-f]{40}")) })
        require(lines.size in 2..(GitSyncLimits.MaxFiles + 1))
        val files = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 5)
            val path = Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8)
            require(GitSyncPathPolicy.isAllowed(path))
            require(parts[3] in setOf("0", "1") && parts[4] in setOf("0", "1"))
            GitConflictFile(
                path = path,
                localModifiedAtMillis = parts[1].takeUnless { it == "-" }?.toLong(),
                remoteModifiedAtMillis = parts[2].takeUnless { it == "-" }?.toLong(),
                localExists = parts[3] == "1",
                remoteExists = parts[4] == "1",
            )
        }
        require(files.map { it.path }.distinct().size == files.size)
        require(files.all { (it.localModifiedAtMillis ?: 0L) >= 0L && (it.remoteModifiedAtMillis ?: 0L) >= 0L })
        GitConflictDetails(header[0], header[1], files)
    }.getOrNull()
}
