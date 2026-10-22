-- SavedLibrary (separate database file), schema v1.
-- This reviewed SQL contract is not a generated Room identity bundle.
-- Room/KSP exports app/schemas/me.ash.reader.infrastructure.library.SavedLibraryDatabase/1.json
-- during the authorized Android build. Reader DB7 and its migration history are unchanged.
CREATE TABLE IF NOT EXISTS saved_item (
    id TEXT NOT NULL PRIMARY KEY,
    normalizedUrl TEXT NOT NULL,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    sourceName TEXT,
    excerpt TEXT,
    savedAt INTEGER NOT NULL,
    location TEXT NOT NULL,
    isRead INTEGER NOT NULL,
    contentState TEXT NOT NULL,
    imageState TEXT NOT NULL,
    thumbnailPath TEXT,
    lastError TEXT,
    contentRevision TEXT,
    fileRevision TEXT,
    readingAnchor TEXT,
    downloadToken TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS index_saved_item_normalizedUrl ON saved_item(normalizedUrl);
CREATE INDEX IF NOT EXISTS index_saved_item_location_savedAt ON saved_item(location, savedAt);
