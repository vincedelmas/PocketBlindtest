"""Exercise the app's actual SQLite schema and queries without an Android build."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/vincedelmas/pocketblindtest/GameHistory.java').read_text()


def java_sql(source_expression, **values):
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|[A-Za-z]+', source_expression)
    return ''.join(json.loads(t) if t.startswith('"') else values[t] for t in tokens)


class HistorySqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        for statement in re.findall(r'db.execSQL\("([^"]+)"\)', SOURCE):
            self.db.execute(statement)
        self.db.executemany('INSERT INTO sessions VALUES (?, ?, ?)', [('one', 5, 1), ('two', 0, 2)])
        self.add('a', 'one', 'Rock', 1999, 1, 1000)
        self.add('b', 'one', 'rock', 2000, 0, 16000)
        self.add('c', 'two', 'Unknown', 0, 1, 4000)

    def tearDown(self):
        self.db.close()

    def add(self, key, session, genre, year, found, clip):
        self.db.execute('INSERT OR IGNORE INTO results VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, 1, 1)',
                        (key, session, 'Song', 'Artist', genre, year, found, clip))

    def totals(self, session=None):
        expression = re.findall(r'rawQuery\((.+),args\)', SOURCE)[0]
        query = java_sql(expression, where='' if session is None else ' WHERE session=?')
        return self.db.execute(query, () if session is None else (session,)).fetchone()

    def groups(self, expression):
        query_expression = re.findall(r'rawQuery\((.+),args\)', SOURCE)[1]
        return self.db.execute(java_sql(query_expression, expression=expression, where='')).fetchall()

    def test_session_and_global_totals_and_success_only_mean(self):
        self.assertEqual(self.totals('one'), (2, 1, 1000.0))
        self.assertEqual(self.totals(), (3, 2, 2500.0))
        self.assertEqual(self.totals('missing'), (0, 0, None))

    def test_round_saved_twice_does_not_double_count(self):
        self.add('a', 'one', 'Rock', 1999, 1, 1000)
        self.assertEqual(self.totals(), (3, 2, 2500.0))

    def test_report_reverses_finished_round_everywhere(self):
        self.db.execute('DELETE FROM results WHERE id=?', ('a',))
        self.assertEqual(self.totals('one'), (1, 0, None))
        self.assertEqual(self.totals(), (2, 1, 4000.0))
        self.db.execute('DELETE FROM results WHERE id=?', ('never-recorded',))
        self.assertEqual(self.totals()[0], 2)
        self.add('replacement', 'one', 'Jazz', 2004, 1, 2000)
        self.assertEqual(self.totals('one'), (2, 1, 2000.0))

    def test_genres_ignore_case(self):
        groups = self.groups('genre COLLATE NOCASE')
        self.assertEqual(groups[0][1:], (2, 1, 1000.0))
        self.assertEqual(len(groups), 2)

    def test_year_boundaries_and_missing_tags(self):
        expressions = re.findall(r'groups\("([^"]+)",where,args\)', SOURCE)
        periods = {row[0] for row in self.groups(expressions[1])}
        decades = {row[0] for row in self.groups(expressions[2])}
        self.assertEqual(periods, {'1995–1999', '2000–2004', 'Unknown'})
        self.assertEqual(decades, {'1990s', '2000s', 'Unknown'})

    def test_history_retained_beyond_song_display_limit(self):
        for i in range(60):
            self.add(str(i), 'two', 'Jazz', 2001, 1, 2000)
        self.assertEqual(self.totals('two')[0], 61)
        statement = next(q for q in re.findall(r'rawQuery\("([^"]+)"', SOURCE) if 'ORDER BY ended' in q)
        self.assertEqual(len(self.db.execute(statement, ('two',)).fetchall()), 50)


if __name__ == '__main__':
    unittest.main()
