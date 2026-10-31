package ca.fuwafuwa.gaku;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import org.junit.Assume;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import ca.fuwafuwa.gaku.Database.IDatabaseHelper;
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryOptimized;
import ca.fuwafuwa.gaku.Database.JmDictDatabase.Models.EntryReading;
import ca.fuwafuwa.gaku.XmlParsers.Interfaces.DictParser;
import ca.fuwafuwa.gaku.XmlParsers.JmDict.JmParser;
import ca.fuwafuwa.gaku.XmlParsers.KanjiDict2.Kd2Parser;

public class GenerateDictionary {
    class DatabaseHelperImpl implements IDatabaseHelper {

        ConnectionSource mConnectionSource;

        DatabaseHelperImpl(ConnectionSource connectionSource) {
            mConnectionSource = connectionSource;
        }

        @Override
        public <T> Dao<T, Integer> getDbDao(Class clazz) throws SQLException {
            return DaoManager.createDao(mConnectionSource, clazz);
        }
    }

    /**
     * Not a real test: builds the dictionary database bundled in the app's assets from the EDRDG's
     * JMdict_e and KANJIDIC2 files. tools/update_dictionary.sh runs it with the files' paths
     * (-Pgaku.jmdict, -Pgaku.kanjidic2, -Pgaku.dictionaryOut); without them it's skipped.
     */
    @Test
    public void generateDic() throws Exception {

        String jmdictPath = System.getProperty("gaku.jmdict");
        String kanjidic2Path = System.getProperty("gaku.kanjidic2");
        String dbPath = System.getProperty("gaku.dictionaryOut");
        Assume.assumeTrue("Run by tools/update_dictionary.sh", jmdictPath != null && kanjidic2Path != null && dbPath != null);

        String databaseUrl = String.format("jdbc:sqlite:%s", dbPath);
        Files.deleteIfExists(Paths.get(dbPath));

        ConnectionSource connectionSource = null;
        try {
            connectionSource = new JdbcConnectionSource(databaseUrl);
            TableUtils.createTable(connectionSource, EntryOptimized.class);
            TableUtils.createTable(connectionSource, EntryReading.class);

            DatabaseHelperImpl dbHelper = new DatabaseHelperImpl(connectionSource);

            // JMdict first, so its entries keep the lower ids, as in the database Kaku shipped
            parse(connectionSource, new JmParser(dbHelper), jmdictPath);
            parse(connectionSource, new Kd2Parser(dbHelper), kanjidic2Path);
        } finally {
            if (connectionSource != null) {
                connectionSource.close();
            }
        }

        // What Android's SQLiteOpenHelper expects of the copy in filesDir: the locale table, and
        // JmDatabaseHelper's DATABASE_VERSION, so it doesn't try to create or upgrade the database
        try (Connection connection = DriverManager.getConnection(databaseUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE android_metadata (locale TEXT)");
            statement.execute("INSERT INTO android_metadata VALUES ('en_US')");
            statement.execute("PRAGMA user_version = 1");
            statement.execute("VACUUM");
        }
    }

    private void parse(ConnectionSource connectionSource, DictParser dictParser, String xmlPath) throws Exception {
        try (FileInputStream xml = new FileInputStream(xmlPath)) {
            XmlPullParser parser = new KXmlParser();
            parser.setInput(xml, "UTF-8");

            // One transaction per file, as ParserRunnable did: committing each entry on its own takes hours
            TransactionManager.callInTransaction(connectionSource, () -> {
                dictParser.parseDict(parser);
                return null;
            });
        }
    }
}
