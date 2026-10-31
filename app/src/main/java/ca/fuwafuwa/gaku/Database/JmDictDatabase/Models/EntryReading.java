package ca.fuwafuwa.gaku.Database.JmDictDatabase.Models;

import com.j256.ormlite.field.DatabaseField;

/**
 * One reading of a JMdict entry that has a written form in kanji, pointing at the row of its first
 * written form the reading applies to. Lets words written in kana be looked up by reading, so that
 * よけい finds 余計: EntryOptimized only matches by written form.
 */
public class EntryReading {

    @DatabaseField(index = true)
    private String reading;

    @DatabaseField
    private int entryId;

    public EntryReading(){
    }

    public EntryReading(String reading, int entryId){
        this.reading = reading;
        this.entryId = entryId;
    }

    public String getReading() {
        return reading;
    }

    public int getEntryId() {
        return entryId;
    }
}
