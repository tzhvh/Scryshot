# ScreenshotGo Context

A mobile utility app for local, on-device screenshot indexing, organization, and search.

## Language

**Screenshot**:
An image captured of the device's screen, containing text to be recognized, indexed, and searched.
_Avoid_: Image, photo

**OCR (Optical Character Recognition)**:
The process of extracting text content from Screenshots locally on-device.
_Avoid_: Text Recognition, cloud OCR

**Index Scanner**:
Coordinates automated extraction of flat, searchable text from Screenshots into the SQLite FTS index. Runs in two variants: foreground (`ForegroundScanner`, conflated channel driven by `LiveData`) and background (`BackgroundScanner`, `ListenableWorker`).
_Avoid_: Scanner alone (too ambiguous)

**Detail OCR**:
On-demand, coordinate-rich text recognition triggered when a user enters "Text Mode" on the screenshot detail page. Produces `Text.TextBlock`/`Line`/`Element` with bounding boxes for the interactive overlay — not persisted, re-run each time.
_Avoid_: Scanner, OCR pass
