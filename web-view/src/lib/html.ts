/** Azure DevOps work item fields (System.Description, etc.) come back as HTML — this strips tags
 * down to plain text so the content is safe to show inline and safe to send to the AI as text.
 * Collapses horizontal whitespace per line but keeps line breaks, so paragraphs and checklist
 * items stay readable instead of collapsing into one wall of text. */
export function stripHtml(html: string): string {
  const text = new DOMParser().parseFromString(html, "text/html").body.textContent ?? "";
  return text
    .split("\n")
    .map((line) => line.replace(/[ \t]+/g, " ").trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
