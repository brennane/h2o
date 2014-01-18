package water.api;

import water.*;
import water.fvec.Frame;

import com.google.gson.JsonObject;

public class SetColumnNames extends Request {

  protected final H2OHexKey _tgtKey = new H2OHexKey("target");

  private class HeaderKey extends H2OHexKey {
    public HeaderKey(){super("source");}
    @Override protected ValueArray parse(String input) throws IllegalArgumentException {
      ValueArray res = super.parse(input);
      if(res.numCols() != _tgtKey.value().numCols())
        throw new IllegalArgumentException("number of columns don't match!");
      return res;
    }
  }
  protected final HeaderKey _srcKey = new HeaderKey();

  @Override protected Response serve() {
    ValueArray tgt = _tgtKey.value();
    String[] names = _srcKey.value().colNames();
    tgt.setColumnNames(names);
    Key frKey = ValueArray.makeFRKey(tgt._key);
    Frame fr = DKV.get(frKey).get();
    fr._names = names;

    // Must write in the new header.  Must use DKV instead of UKV, because do
    // not want to delete the underlying data.
    Futures fs = new Futures();
    DKV.put(tgt._key,tgt,fs);
    DKV.put(   frKey,fr ,fs);
    fs.blockForPending();
    return Inspect.redirect(new JsonObject(), frKey);
  }
  public static String link(Key k, String s){
    return "<a href='SetColumnNames.query?target="+k+"'>" + s + "</a>";
  }
}
