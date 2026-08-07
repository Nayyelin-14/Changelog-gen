/** Matches automated/CI commit authors (Azure Pipelines release-bump commits, Dependabot,
 * Renovate, etc.) — used both to keep contributor counts honest and to keep bot noise out of the
 * raw commit text sent to the AI for changelog generation. */
export const BOT_AUTHOR_PATTERN =
  /(?:\bbot\b|azure pipelines|azure-devops|dependabot|renovate|github-actions|\[bot\]|\[DevOps\])/i;

export function isBotAuthor(author: string | null | undefined): boolean {
  return !!author && BOT_AUTHOR_PATTERN.test(author);
}
