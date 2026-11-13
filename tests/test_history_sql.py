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
        create_body = SOURCE.split('public void onCreate', 1)[1].split('@Override public void onUpgrade', 1)[0]
        for statement in re.findall(r'db.execSQL\("([^"]+)"\)', create_body):
            self.db.execute(statement)
        self.db.executemany('INSERT INTO sessions (id,size,started) VALUES (?, ?, ?)', [('one', 5, 1), ('two', 0, 2)])
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

    def scoped_totals(self, mode='song', genres=''):
        body = SOURCE.split('Summary summary(String session,String mode,String genres)', 1)[1]
        where = re.search(r'String where=session==null\?"([^"]+)"', body).group(1)
        expression = re.findall(r'rawQuery\((.+),args\)', SOURCE)[0]
        return self.db.execute(java_sql(expression, where=where), (mode, genres)).fetchone()

    def test_mode_and_exact_genre_selection_are_isolated(self):
        for key, mode, genres in [('rock', 'song', '["rock"]'),
                                  ('mix', 'song', '["jazz","rock"]'),
                                  ('artist', 'artist', ''),
                                  ('artist-rock', 'artist', '["rock"]')]:
            self.db.execute('INSERT INTO sessions VALUES (?,5,3,?,?)', (key, mode, genres))
            self.add(key, key, 'Rock', 2000, 1, 2000)
        self.assertEqual(self.scoped_totals(), (3, 2, 2500.0))
        self.assertEqual(self.scoped_totals('song', '["rock"]'), (1, 1, 2000.0))
        self.assertEqual(self.scoped_totals('song', '["jazz","rock"]'), (1, 1, 2000.0))
        self.assertEqual(self.scoped_totals('artist'), (1, 1, 2000.0))
        self.assertEqual(self.scoped_totals('artist', '["rock"]'), (1, 1, 2000.0))
        expression = next(q for q in re.findall(r'rawQuery\("([^"]+)"', SOURCE) if 'ORDER BY s.started' in q)
        self.assertEqual({r[0] for r in self.db.execute(expression, ('song', ''))}, {'one', 'two'})

    def test_v1_migration_preserves_history_under_song_all_genres(self):
        old = sqlite3.connect(':memory:')
        old.execute('CREATE TABLE sessions (id TEXT PRIMARY KEY,size INTEGER NOT NULL,started INTEGER NOT NULL)')
        old.execute("INSERT INTO sessions VALUES ('legacy',5,1)")
        upgrade = SOURCE.split('public void onUpgrade', 1)[1].split('String start', 1)[0]
        for statement in re.findall(r'db.execSQL\("([^"]+)"\)', upgrade):
            old.execute(statement)
        self.assertEqual(old.execute('SELECT * FROM sessions').fetchone(), ('legacy', 5, 1, 'song', ''))
        old.close()

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


    def test_reset_deletes_all_results_and_sessions_and_allows_new_games(self):
        reset_body = SOURCE.split('void reset() {', 1)[1].split('void remove(', 1)[0]
        statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', reset_body)
        self.assertEqual(len(statements), 2)
        self.db.commit()
        with self.db:
            for statement in statements:
                self.db.execute(statement)
        self.assertEqual(self.totals(), (0, 0, None))
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0], 0)
        self.db.execute('INSERT INTO sessions (id,size,started) VALUES (?, ?, ?)', ('new', 5, 3))
        self.add('new-round', 'new', 'Jazz', 2004, 1, 2000)
        self.assertEqual(self.totals('new'), (1, 1, 2000.0))

if __name__ == '__main__':
    unittest.main()
