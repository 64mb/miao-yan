package com.tw93.miaoyan.android.git

object GitContentGate {
    fun validate(entries: Map<String, Long>, source: String) {
        validateStructure(entries, source)
        validateAttachmentSizes(entries, source)
    }

    fun validateStructure(entries: Map<String, Long>, source: String) {
        if (entries.size > GitSyncLimits.MaxFiles) {
            throw GitSyncException.Limit("$source contains more than ${GitSyncLimits.MaxFiles} files.")
        }
        val collisionKeys = mutableSetOf<String>()
        entries.forEach { (path, size) ->
            if (!GitSyncPathPolicy.isAllowed(path)) {
                throw GitSyncException.Storage("$source contains a path outside the allowlist: $path")
            }
            if (size < 0) {
                throw GitSyncException.Storage("$source contains an invalid file size: $path")
            }
            if (!collisionKeys.add(GitSyncPathPolicy.collisionKey(path))) {
                throw GitSyncException.Conflict(message = "$source contains case-colliding paths: $path")
            }
        }
    }

    fun validateAttachmentSizes(entries: Map<String, Long>, source: String) {
        entries.forEach { (path, size) ->
            if (GitSyncPathPolicy.isAttachment(path) && size > GitSyncLimits.MaxAttachmentBytes) {
                throw GitSyncException.Limit("$source contains an added or changed attachment larger than 25 MiB: $path")
            }
        }
    }
}
