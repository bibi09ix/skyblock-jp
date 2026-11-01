# Translation provider prompts

The versioned UTF-8 JSON resources contain ordered `segments`. Concatenating
these segments without separators produces the exact instructions submitted to
the provider. Segment boundaries are only for reviewing diffs.

The v1 texts were moved unchanged from the 0.7.12 Java literals. Golden SHA-256
tests preserve that baseline. Change the prompt version and its evaluation
fixtures deliberately when changing instructions; do not mix prompt changes into
Java formatting or transport refactors.

The item prompt receives a tooltip paragraph. The NPC and player-chat prompts
receive separate JSON fields for speaker, previous dialogue and current text.
Those fields are untrusted material to translate, never additional instructions.
No prompt resource contains API keys or recorded personal conversations.
