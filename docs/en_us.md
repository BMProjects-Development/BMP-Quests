# BMP Quests guide

[Home](../README.md) · [Русский](ru_ru.md) · [Testing](testing.md)

## Install

Use the JAR matching your loader, on the server and every client. Install FTB Quests and its normal dependencies. Existing books load with their original image behavior until you add BMP visibility settings. The supported FTB release is declared in the mod metadata; use the matching version when updating a pack.

## Conditional chapter images

Enable FTB Quests editing mode, right-click an image and choose Edit. Expand **Visibility**.

| Mode | When the image appears |
| --- | --- |
| Always Visible | Original FTB image behavior |
| Follow Quest Visibility | The linked quest passes FTB's own visibility check |
| Quest Available | The team can start the quest, or has already completed it |
| Quest Completed | The team has completed the quest |
| Multiple Conditions | The configured list passes ALL or ANY checks |

For a decorative frame around a hidden quest, choose **Follow Quest Visibility**, click **Linked quest**, and select the quest using the normal picker. Accept the image properties.

For a chapter illustration that needs two milestones, select **Multiple Conditions**. Open **Conditions**, add two entries, and set each entry's quest and check. Keep ANY disabled for ALL (AND); enable it for ANY (OR). An empty list in this mode is hidden.

Missing quests make their condition false and produce a warning in the image's editor tooltip. References remain stored so removing a quest does not silently make the image public. Hidden images remain selectable and appear translucent in editing mode. Outside editing mode they are neither rendered nor clickable. Conditions follow server-synchronized team progress and update without reopening the book.

To link quickly, Ctrl-click a quest to select it, then right-click the image and choose **Follow selected quest visibility**. To edit several images, Ctrl-click them and choose **Bulk Edit Images** from an image's menu. **Clear image conditions** restores original image behavior and asks for confirmation.

FTB's existing editors-only and image dependency properties still apply alongside BMP's conditions.

## Bulk quest editing

Select several quests using FTB's selection controls (Ctrl-click, drag selection, or Ctrl+A). Right-click a quest and choose **Bulk Edit**. The title shows the selected quest count and current editing locale. Up to 256 quests can be processed together.

The native configuration editor groups options under General, Text, Rewards, Dependencies and Visibility. **Current value** displays Mixed when the selection differs. New values are separate drafts. Enable **Apply this field** for each property or text field you want to change; other fields remain untouched.

For example, to prefix quest titles with `[Chapter 2] `, expand Text → Title, enable the field, select Add Prefix and enter the text. To append a description paragraph, use Text → Description → Append and enter multiple lines in the original multiline editor. Find and Replace uses literal text rather than regular expressions. Only the current editing locale changes; other languages remain intact.

Choose an operation in Rewards or Dependencies to enable that category. Add rewards through **Configure reward to add**, which opens FTB's own reward type picker and editor. Copying rewards replaces the target sets, skips the source quest if selected, and creates independent reward IDs. Remove-type affects only the chosen reward type. Auto-claim uses the existing FTB reward setting.

Dependencies can be added, removed or cleared. The server checks the whole resulting graph, including references through chapters and tasks. Self-references and cycles reject the batch before changes are applied.

Click **Apply** to submit everything together, or **Cancel** to discard the draft. Replacing text, clearing dependencies and removing/replacing rewards require confirmation. Use the original Undo/Redo buttons to reverse or restore the entire batch as one history action. History belongs to the quest book, following normal FTB behavior.

## Storage and pack distribution

Visibility rules are stored on each chapter image under the namespaced `bmpquests_visibility` key, in the book's existing chapter files. Text stays in FTB's separate language files. Distribute the full quest book, including its `lang` directory, as usual. There is no separate addon database to copy.
