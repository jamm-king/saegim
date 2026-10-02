import remarkGfm from "remark-gfm";
import remarkCjkFriendly from "remark-cjk-friendly/parseOnly";
import remarkBreaks from "remark-breaks";

// Extend parsing without rewriting the stored text or interpreting code as prose.
export const markdownPlugins = [remarkGfm, remarkCjkFriendly, remarkBreaks];
