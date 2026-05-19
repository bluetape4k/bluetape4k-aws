# Source-Verified README Diagrams

## Context

The AWS class diagram still used invented extension-class labels that do not exist in the source tree.

## Decision

Replace generated filler names with current source file/API anchors: `DynamoDbAsyncTableExtensions` and `SqsAsyncClientCoroutinesExtensions`.

## Verification

Before publishing, grep the source tree for all prominent diagram class labels, parse the SVG, and rerender the PNG from the updated SVG.

## Future Guidance

Do not invent `*Ext` class labels for Kotlin extension files. Use the actual file name or the receiver type plus the function names.
