"""Run the activity's platform-independent selection and guessing code on a JVM."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/vincedelmas/pocketblindtest/MainActivity.java').read_text()


def method(name):
    start = SOURCE.index('    private ' + name)
    body = SOURCE.index('{', start)
    depth = 1
    end = body + 1
    while depth:
        depth += (SOURCE[end] == '{') - (SOURCE[end] == '}')
        end += 1
    return SOURCE[start:end]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class GameplayTest(unittest.TestCase):
    def test_genre_filter_artist_answers_and_deck_cycles(self):
        methods = '\n'.join(method(name) for name in [
            'static String artistKey(', 'static boolean knownArtist(',
            'List<Track> eligibleTracks(', 'List<Track> sessionPool(',
            'int poolCount(', 'void nextRound(', 'void guess(', 'static String normalize(',
        ])
        harness = r'''
import java.util.*;
import java.text.Normalizer;
public class GameplayCheck {
    record Track(long id,String title,String artist,String genre,int year) {
        String label() { return title+" — "+artist; }
    }
    final ArrayList<Track> tracks=new ArrayList<>(), bag=new ArrayList<>();
    final HashSet<Long> excluded=new HashSet<>();
    final HashSet<String> usedArtists=new HashSet<>();
    final TreeSet<String> sessionGenres=new TreeSet<>();
    final Random random=new Random(42);
    boolean artistMode, exhausted, finished, solved;
    int sessionSize, rounds, attempt;
    Track target;
    String roundId, sessionId="session";
    void stopAudio() {}
    void showStats(String id) {}
    void saveGame() {}
    void toast(String s) {}
    void renderRound() {}
    void finishRound() { finished=true; }
    void miss(String s) {}
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    void complete() {
        rounds++;
        if(artistMode) usedArtists.add(artistKey(target.artist));
    }
''' + methods + r'''
    public static void main(String[] args) {
        GameplayCheck g=new GameplayCheck();
        g.tracks.add(new Track(1,"One","  Artist A ","Rock",2000));
        g.tracks.add(new Track(2,"Two","artist a","rock",2001));
        g.tracks.add(new Track(3,"Three","Artist B","Jazz",2002));
        g.tracks.add(new Track(4,"Four","<unknown>","Unknown",0));
        g.tracks.add(new Track(5,"Five","Unknown artist","Rock",2000));
        g.tracks.add(new Track(6,"Six","Artist C","Unknown",0));
        check(g.poolCount(false,Set.of())==6);
        check(g.poolCount(true,Set.of())==3);
        check(g.poolCount(true,Set.of("rock"))==1);
        check(g.poolCount(false,Set.of("rock"))==3);
        check(g.poolCount(true,Set.of("unknown"))==1);
        check(!knownArtist(" UNKNOWN ") && !knownArtist(" "));
        check(!artistKey("Simon & Garfunkel").equals(artistKey("Simon")));

        g.artistMode=true; g.sessionSize=3;
        Set<String> seen=new HashSet<>();
        for(int i=0;i<3;i++) {
            g.nextRound();
            check(seen.add(artistKey(g.target.artist)));
            check(g.attempt==0 && !g.finished && !g.solved);
            g.complete();
        }
        check(g.bag.isEmpty() && seen.size()==3);

        g=new GameplayCheck();
        g.artistMode=true;
        g.tracks.add(new Track(1,"Title A","Artist A","Rock",0));
        g.tracks.add(new Track(2,"Different title"," artist a ","Rock",0));
        g.tracks.add(new Track(3,"Title B","Artist B","Rock",0));
        String previous="";
        for(int i=0;i<30;i++) {
            g.nextRound();
            String artist=artistKey(g.target.artist);
            check(!artist.equals(previous));
            previous=artist;
            g.complete();
        }
        g.target=g.tracks.get(0); g.finished=false;
        g.guess(g.tracks.get(1)); check(g.solved && g.finished);
        g.solved=false; g.finished=false;
        g.guess(g.tracks.get(2)); check(!g.solved && !g.finished);
        g.artistMode=false;
        g.guess(g.tracks.get(1)); check(!g.solved && !g.finished);

        g=new GameplayCheck();
        g.tracks.add(new Track(1,"A","A","Rock",0));
        g.tracks.add(new Track(2,"B","B","Jazz",0));
        g.sessionGenres.add("rock");
        g.nextRound(); check(g.target.id==1);
        g.excluded.add(1L); g.complete();
        g.nextRound(); check(g.exhausted && g.target==null);
    }
}
'''
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'GameplayCheck.java'
            path.write_text(harness)
            subprocess.run(['javac', '--release', '17', str(path)], check=True, capture_output=True, text=True)
            subprocess.run(['java', '-cp', folder, 'GameplayCheck'], check=True, capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
