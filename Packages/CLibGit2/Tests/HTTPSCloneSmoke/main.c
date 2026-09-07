#include <git2.h>
#include <stdio.h>

int main(int argc, char **argv)
{
	git_repository *repository = NULL;
	const git_error *error;
	int major = 0;
	int minor = 0;
	int revision = 0;
	int result;

	if (argc != 3) {
		fprintf(stderr, "usage: %s <https-url> <destination>\n", argv[0]);
		return 64;
	}

	git_libgit2_version(&major, &minor, &revision);
	if (git_libgit2_init() < 0) {
		fprintf(stderr, "git_libgit2_init failed\n");
		return 1;
	}

	result = git_clone(&repository, argv[1], argv[2], NULL);
	if (result != 0) {
		error = git_error_last();
		fprintf(
			stderr,
			"libgit2 %d.%d.%d HTTPS clone failed: %s\n",
			major,
			minor,
			revision,
			error != NULL ? error->message : "unknown error"
		);
		git_libgit2_shutdown();
		return 1;
	}

	git_repository_free(repository);
	git_libgit2_shutdown();
	printf("libgit2 %d.%d.%d: HTTPS clone succeeded\n", major, minor, revision);
	return 0;
}
