# ScreenshotGo Context

A mobile utility app for local, on-device screenshot indexing, organization, and search.

## Language

### Core Concepts

**Screenshot**:
An image captured of the device's screen, containing text to be recognized, indexed, and searched.
_Avoid_: Image, photo

**Collection**:
A user-defined or default grouping for organizing Screenshots within the application.
_Avoid_: Folder, directory (when referring to application-level categorization)

### Analysis & Processing

**OCR (Optical Character Recognition)**:
The process of extracting text content from Screenshots locally on-device.
_Avoid_: Text Recognition, cloud OCR

**Detail OCR**:
On-demand, coordinate-rich text recognition triggered when a user enters "Text Mode" on the screenshot detail page. Produces bounding boxes for the interactive overlay, re-run each time and not persisted.
_Avoid_: Scanner, OCR pass

**Image Embedding**:
A dense vector representation of a Screenshot's visual content generated locally, enabling visual similarity search.
_Avoid_: Image labels, visual tag

### Search & Storage

**zvec**:
The in-process vector database engine used for local indexing, vector embeddings, full-text search, and scalar filtering.
_Avoid_: SQLite, Room (after migration)

**Content Hash**:
The unique SHA-256 identifier computed from the bytes of a Screenshot, serving as its stable, source-agnostic identity.
_Avoid_: File path, MediaStore URI, UUID (for identity)

**Hybrid Search**:
A retrieval mechanism that queries multiple modalities (OCR text, Image Embeddings, and metadata) and fuses their rankings into a single result list.
_Avoid_: Keyword search, text search

### Ingestion & Presentation

**Index Scanner**:
Coordinates automated extraction of flat, searchable text from Screenshots into the FTS index. Runs in foreground and background variants.
_Avoid_: Scanner alone (too ambiguous)

**MediaStore Ingestion**:
Automatic discovery and import of Screenshots from standard device folders using the system media database.
_Avoid_: Broad scanning, background monitoring (general)

**SAF (Storage Access Framework) Ingestion**:
An opt-in discovery mechanism where the user grants access to a specific folder tree which the app monitors for changes.
_Avoid_: File provider, file system polling

**Shadow Gallery**:
An architecture where the gallery UI is backed by a local database and loads thumbnails from app-private storage, bypassing slow storage APIs during scrolling.
_Avoid_: Direct gallery, SAF gallery

**Thumbnail Cache**:
A local, app-private directory of compressed WebP/JPEG images keyed by Content Hash used to render the gallery instantly without IPC overhead.
_Avoid_: MediaStore thumbnails
