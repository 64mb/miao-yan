package com.tw93.miaoyan.android.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.EmptyTreeIterator

internal object GitIndexDiff {
    /**
     * JGit's cached diff resolves `HEAD^{tree}` by default. That throws
     * `NoHeadException("Cannot read tree {}")` for a correctly initialized repository whose
     * main branch has not received its first commit yet. Diff the index against an explicit empty
     * tree in that state so the first sync can create and push the initial commit.
     */
    fun stagedChanges(git: Git, repository: Repository): List<DiffEntry> =
        git.diff().setCached(true).apply {
            if (repository.resolve(Constants.HEAD) == null) setOldTree(EmptyTreeIterator())
        }.call()
}
