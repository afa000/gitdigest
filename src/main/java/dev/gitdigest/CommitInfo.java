package dev.gitdigest;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * One commit's worth of data, extracted from JGit and independent of it.
 * The rest of the app only ever sees this record — never JGit types.
 *
 * @param sha          full 40-char commit hash
 * @param authorName   e.g. "Ada Lovelace"
 * @param authorEmail  e.g. "ada@example.com"
 * @param when         author timestamp
 * @param subject      first line of the commit message
 * @param filesChanged paths touched by this commit (empty until Step 5)
 */
public record CommitInfo(
        String sha,
        String authorName,
        String authorEmail,
        ZonedDateTime when,
        String subject,
        List<String> filesChanged) {
}
