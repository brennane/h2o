package water;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import water.deploy.*;
import water.fvec.*;
import water.parser.ParseDataset;
import water.util.Log;

import com.google.common.io.Closeables;

public class TestUtil {
  private static int _initial_keycnt = 0;

  protected static void startCloud(String[] args, int nnodes) {
    for( int i = 1; i < nnodes; i++ ) {
      Node n = new NodeVM(args);
      n.inheritIO();
      n.start();
    }
    H2O.waitForCloudSize(nnodes);
  }

  @BeforeClass public static void setupCloud() {
    H2O.main(new String[] {});
    _initial_keycnt = H2O.store_size();
    assert Job.all().length == 0;      // No outstanding jobs
  }

  @AfterClass public static void checkLeakedKeys() {
    Job[] jobs = Job.all();
    for( Job job : jobs )
      assert job.end_time != 0 : ("UNFINSIHED JOB: " + job.job_key + " " + job.description + ", end_time = " + job.end_time);  // No pending job
    DKV.remove(Job.LIST);         // Remove all keys
    DKV.remove(Log.LOG_KEY);
    DKV.write_barrier();
    int leaked_keys = H2O.store_size() - _initial_keycnt;
    if( leaked_keys > 0 ) {
      for( Key k : H2O.keySet() ) {
        Value value = DKV.get(k);
        Object o = value.type() != TypeMap.PRIM_B ? value.get() : "byte[]";
        // Ok to leak VectorGroups
        if( o instanceof Vec.VectorGroup )
          leaked_keys--;
        else
          System.err.println("Leaked key: " + k + " = " + o);
      }
    }
    assertTrue("No keys leaked", leaked_keys <= 0);
    _initial_keycnt = H2O.store_size();
  }

  // Stall test until we see at least X members of the Cloud
  public static void stall_till_cloudsize(int x) {
    stall_till_cloudsize(x, 10000);
  }

  public static void stall_till_cloudsize(int x, long ms) {
    H2O.waitForCloudSize(x, ms);
    UKV.put(Job.LIST, new Job.List()); // Jobs.LIST must be part of initial keys
  }

  public static File find_test_file(String fname) {
    // When run from eclipse, the working directory is different.
    // Try pointing at another likely place
    File file = new File(fname);
    if( !file.exists() )
      file = new File("target/" + fname);
    if( !file.exists() )
      file = new File("../" + fname);
    if( !file.exists() )
      file = new File("../target/" + fname);
    if( !file.exists() )
      file = null;
    return file;
  }

  public static Key[] load_test_folder(String fname) {
    return load_test_folder(find_test_file(fname));
  }

  public static Key[] load_test_folder(File folder) {
    assert folder.isDirectory();
    ArrayList<Key> keys = new ArrayList<Key>();
    for( File f : folder.listFiles() ) {
      if( f.isFile() )
        keys.add(load_test_file(f));
    }
    Key[] res = new Key[keys.size()];
    keys.toArray(res);
    return res;
  }

  public static Key load_test_file(String fname, String key) {
    return load_test_file(find_test_file(fname), key);
  }

  public static Key load_test_file(String fname) {
    return load_test_file(find_test_file(fname));
  }

  public static Key load_test_file(File file, String keyname) {
    Key key = null;
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(file);
      key = ValueArray.readPut(keyname, fis);
    } catch( IOException e ) {
      Closeables.closeQuietly(fis);
    }
    if( key == null )
      fail("failed load to " + file.getName());
    return key;
  }

  public static Key load_test_file(File file) {
    return load_test_file(file, file.getPath());
  }

  public static Key loadAndParseFile(String keyName, String path) {
    Key fkey = load_test_file(path);
    Key okey = Key.make(keyName);
    if( DKV.get(okey) != null )
      DKV.remove(okey);
    ParseDataset.parse(okey, new Key[] { fkey });
    UKV.remove(fkey);
    return okey;
  }

  public static Key loadAndParseFolder(String keyName, String path) {
    Key[] keys = load_test_folder(path);
    Arrays.sort(keys);
    Key okey = Key.make(keyName);
    ParseDataset.parse(okey, keys);
    for( Key k : keys )
      UKV.remove(k);
    return okey;
  }

  public static ValueArray parse_test_key(Key fileKey, Key parsedKey) {
    ParseDataset.parse(parsedKey, new Key[] { fileKey });
    return DKV.get(parsedKey).get();
  }

  public static String replaceExtension(String fname, String newExt) {
    int i = fname.lastIndexOf('.');
    if( i == -1 )
      return fname + "." + newExt;
    return fname.substring(0, i) + "." + newExt;
  }

  public static String getHexKeyFromFile(File f) {
    return replaceExtension(f.getName(), "hex");
  }

  public static String getHexKeyFromRawKey(String str) {
    if( str.startsWith("hdfs://") )
      str = str.substring(7);
    return replaceExtension(str, "hex");
  }

  // --------
  // Build a ValueArray from a collection of normal arrays.
  // The arrays must be all the same length.
  public static ValueArray va_maker(Key frKey, Object... arys) {
    assert frKey.user_allowed();
    Key vaKey = ValueArray.makeVAKey(frKey);
    UKV.remove(frKey);
    Futures fs = new Futures();
    // Gather basic column info, 1 column per array
    ValueArray.Column cols[] = new ValueArray.Column[arys.length];
    char off = 0;
    int numrows = -1;
    for( int i = 0; i < arys.length; i++ ) {
      ValueArray.Column col = cols[i] = new ValueArray.Column();
      col._name = Integer.toString(i);
      col._off = off;
      col._scale = 1;
      col._min = Double.MAX_VALUE;
      col._max = Double.MIN_VALUE;
      col._mean = 0.0;
      Object ary = arys[i];
      if( ary instanceof byte[] ) {
        col._size = 1;
        col._n = ((byte[]) ary).length;
      } else if( ary instanceof float[] ) {
        col._size = -4;
        col._n = ((float[]) ary).length;
      } else if( ary instanceof double[] ) {
        col._size = -8;
        col._n = ((double[]) ary).length;
      } else if( ary instanceof String[] ) {
        col._size = 2; // Catagorical: assign size==2
        col._n = ((String[]) ary).length;
        col._domain = new String[0];
      } else if( ary instanceof short[] ) {
        // currently using size==2 (shorts) for Enums instead
        throw H2O.unimpl();
      } else {
        throw H2O.unimpl();
      }
      off += Math.abs(col._size);
      if( numrows == -1 )
        numrows = (int) col._n;
      else
        assert numrows == col._n;
    }

    int rowsize = off;
    ValueArray ary = new ValueArray(vaKey, numrows, rowsize, cols);
    int row = 0;

    for( int chunk = 0; chunk < ary.chunks(); chunk++ ) {
      // Compact data into VA format, and compute min/max/mean
      int rpc = ary.rpc(chunk);
      int limit = row + rpc;
      AutoBuffer ab = new AutoBuffer(rpc * rowsize);

      for( ; row < limit; row++ ) {
        for( int j = 0; j < arys.length; j++ ) {
          ValueArray.Column col = cols[j];
          double d;
          float f;
          byte b;
          switch( col._size ) {
          // @formatter:off
          case  1: ab.put1 (b = ((byte  [])arys[j])[row]);  d = b;  break;
          case -4: ab.put4f(f = ((float [])arys[j])[row]);  d = f;  break;
          case -8: ab.put8d(d = ((double[])arys[j])[row]);          break;
          // @formatter:on
            case 2: // Categoricals or enums
              String s = ((String[]) arys[j])[row];
              String[] dom = col._domain;
              int k = index(dom, s);
              if( k == dom.length ) {
                col._domain = dom = Arrays.copyOf(dom, k + 1);
                dom[k] = s;
              }
              ab.put2((short) k);
              d = k;
              break;
            default:
              throw H2O.unimpl();
          }
          if( d > col._max )
            col._max = d;
          if( d < col._min )
            col._min = d;
          col._mean += d;
        }
      }

      Key ckey = ary.getChunkKey(chunk);
      DKV.put(ckey, new Value(ckey, ab.bufClose()),fs);
    }

    // Sum to mean
    for( ValueArray.Column col : cols )
      col._mean /= col._n;

    // 2nd pass for sigma. Sum of squared errors, then divide by n and sqrt
    for( int i = 0; i < numrows; i++ ) {
      for( int j = 0; j < arys.length; j++ ) {
        ValueArray.Column col = cols[j];
        double d;
        switch( col._size ) {
        // @formatter:off
          case  1: d = ((byte  [])arys[j])[i];  break;
          case  2: d = index(col._domain,((String[])arys[j])[i]);  break;
          case -4: d = ((float [])arys[j])[i];  break;
          case -8: d = ((double[])arys[j])[i];  break;
          default: throw H2O.unimpl();
          // @formatter:on
        }
        col._sigma += (d - col._mean) * (d - col._mean);
      }
    }
    // RSS to sigma
    for( ValueArray.Column col : cols )
      col._sigma = Math.sqrt(col._sigma / (col._n - 1));

    // Write out data & keys
    ary.close(frKey,fs);
    return ary;
  }

  static int index(String[] dom, String s) {
    for( int k = 0; k < dom.length; k++ )
      if( dom[k].equals(s) )
        return k;
    return dom.length;
  }

  // Make a M-dimensional data grid, with N points on each dimension running
  // from 0 to N-1. The grid is flattened, so all N^M points are in the same
  // ValueArray. Add a final column which is computed by running an expression
  // over the other columns, typically this final column is the input to GLM
  // which then attempts to recover the expression.
  public abstract static class DataExpr {
    public abstract double expr(byte[] cols);
  }

  @SuppressWarnings("cast") public ValueArray va_maker(Key key, int M, int N, DataExpr expr) {
    if( N <= 0 || N > 127 || M <= 0 )
      throw H2O.unimpl();
    long Q = 1;
    for( int i = 0; i < M; i++ ) {
      Q *= N;
      if( (long) (int) Q != Q )
        throw H2O.unimpl();
    }
    byte[][] x = new byte[M][(int) Q];
    double[] d = new double[(int) Q];

    byte[] bs = new byte[M];
    int q = 0;
    int idx = M - 1;
    d[q++] = expr.expr(bs);
    while( idx >= 0 ) {
      if( ++bs[idx] >= N ) {
        bs[idx--] = 0;
      } else {
        idx = M - 1;
        for( int i = 0; i < M; i++ )
          x[i][q] = bs[i];
        d[q++] = expr.expr(bs);
      }
    }
    Object[] arys = new Object[M + 1];
    for( int i = 0; i < M; i++ )
      arys[i] = x[i];
    arys[M] = d;
    return va_maker(key, arys);
  }

  // Fluid Vectors

  public static Frame parseFromH2OFolder(String path) {
    File file = new File(VM.h2oFolder(), path);
    return parseFrame(null, file);
  }

  public static Frame parseFrame(File file) {
    return parseFrame(null, file);
  }

  public static Frame parseFrame(Key okey, String path) {
    return parseFrame(okey, new File(path));
  }

  public static Frame parseFrame(Key okey, File file) {
    if( !file.exists() )
      throw new RuntimeException("File not found " + file);
    if(okey == null)
        okey = Key.make(file.getName());
    Key fkey = NFSFileVec.make(file);
    try {
      return ParseDataset2.parse(okey, new Key[] { fkey });
    } finally {
      UKV.remove(fkey);
    }
  }

  public static Frame frame(String[] names, double[]... rows) {
    assert names == null || names.length == rows[0].length;
    Futures fs = new Futures();
    Vec[] vecs = new Vec[rows[0].length];
    Key keys[] = new Vec.VectorGroup().addVecs(vecs.length);
    for( int c = 0; c < vecs.length; c++ ) {
      AppendableVec vec = new AppendableVec(keys[c]);
      NewChunk chunk = new NewChunk(vec, 0);
      for( int r = 0; r < rows.length; r++ )
        chunk.addNum(rows[r][c]);
      chunk.close(0, fs);
      vecs[c] = vec.close(fs);
    }
    fs.blockForPending();
    return new Frame(names, vecs);
  }

  public static void dumpKeys(String msg) {
    System.err.println("-->> Store dump <<--");
    System.err.println("    " + msg);
    System.err.println(" Keys: " + H2O.store_size());
    for ( Key k : H2O.keySet()) System.err.println(" * " + k);
    System.err.println("----------------------");
  }
}
