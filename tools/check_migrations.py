"""Run real migration SQL against SQLite and compare Room's exported schema.

This host check complements, and does not replace, Android MigrationTestHelper.
No third-party Python packages or user database/audio files are required.
"""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/com.meetingatlas.data.MeetingDatabase"
MODULE = ROOT / "app/src/main/java/com/meetingatlas/di/StorageModule.kt"

# The target version is derived from the exported schemas, never hard-coded, so a
# future database bump cannot silently leave this checker verifying an old chain.
SCHEMA_VERSIONS = sorted(int(p.stem) for p in SCHEMAS.glob("*.json") if p.stem.isdigit())
LATEST = max(SCHEMA_VERSIONS) if SCHEMA_VERSIONS else 0


def fresh(version):
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    schema = json.loads((SCHEMAS / f"{version}.json").read_text())["database"]
    for entity in schema["entities"]:
        name = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", name))
    return db


def migrate(db, start):
    source = MODULE.read_text()
    for version in range(start, LATEST):
        marker = f"val MIGRATION_{version}_{version + 1} ="
        if marker not in source:
            raise AssertionError(f"Missing {marker} in {MODULE.name}")
        # The block runs until the matching closing brace at the declaration's indent.
        block = source.split(marker, 1)[1].split("\n    }", 1)[0]
        statements = re.findall(r'db\.execSQL\(("(?:[^"\\]|\\.)*")\)', block)
        if not statements:
            raise AssertionError(f"Migration SQL not found for {version} -> {version + 1}")
        with db:
            for statement in statements:
                db.execute(json.loads(statement))


def signature(db):
    tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    result = {}
    for table in tables:
        # Compare structure, not SQL whitespace/quoting or implicit primary-key index names.
        columns = list(db.execute(f"PRAGMA table_info('{table}')"))
        foreign_keys = sorted(db.execute(f"PRAGMA foreign_key_list('{table}')"))
        indexes = sorted((row[1], row[2], tuple(db.execute(f"PRAGMA index_info('{row[1]}')"))) for row in db.execute(f"PRAGMA index_list('{table}')") if not row[1].startswith("sqlite_"))
        result[table] = (columns, foreign_keys, indexes)
    return result


class MigrationChecks(unittest.TestCase):
    def test_every_version_migrates_to_the_latest_and_keeps_evidence(self):
        expected = fresh(LATEST)
        try:
            for version in range(1, LATEST):
                with self.subTest(version=version), fresh(version) as db:
                    db.execute("INSERT INTO meetings (id,title,createdAt,durationMs,recordingStatus,pinned,updatedAt) VALUES ('existing','Existing meeting',1,1234,'SAVED',0,1)")
                    db.execute("INSERT INTO audio_segments VALUES ('part','existing',1,'/test.m4a',0,1234,4096,'FINALIZED')")
                    if version >= 2:
                        db.execute("INSERT INTO meeting_analysis VALUES ('existing','Original evidence','Edited notes','[]','[]','[]','{}',1,NULL)")
                    migrate(db, version)
                    self.assertEqual(signature(expected), signature(db))
                    self.assertEqual(("Existing meeting",), db.execute("SELECT title FROM meetings").fetchone())
                    self.assertEqual((1234,), db.execute("SELECT durationMs FROM audio_segments").fetchone())
                    if version >= 2:
                        self.assertEqual(("Original evidence", "Edited notes"), db.execute("SELECT transcript,summary FROM meeting_analysis").fetchone())
                    self.assertEqual([], list(db.execute("PRAGMA foreign_key_check")))
        finally:
            expected.close()

    def test_a_migration_exists_for_every_schema_version(self):
        source = MODULE.read_text()
        for version in range(1, LATEST):
            self.assertIn(
                f"val MIGRATION_{version}_{version + 1} =",
                source,
                f"missing migration for schema version {version}",
            )

    def test_orphan_metadata_is_rejected_and_explicit_delete_cascades(self):
        db = fresh(LATEST)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO bookmarks VALUES ('mark','missing',12,'label',1)")
            db.execute("INSERT INTO meetings (id,title,createdAt,durationMs,recordingStatus,pinned,updatedAt) VALUES ('meeting','Title',1,0,'SAVED',0,1)")
            db.execute("INSERT INTO bookmarks VALUES ('mark','meeting',12,'label',1)")
            db.execute("DELETE FROM meetings WHERE id='meeting'")
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM bookmarks").fetchone()[0])
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
