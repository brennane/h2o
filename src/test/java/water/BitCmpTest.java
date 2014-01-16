package water;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BitCmpTest extends TestUtil {
  @Test public void  testBitIdenticalTask(){
    Key k1 = null,k2 = null,k3 = null;
    try {
      k1 = load_test_file("smalldata/stego/stego_training.data","k1");
      k2 = load_test_file("smalldata/stego/stego_training.data","k2");
      k3 = load_test_file("smalldata/stego/stego_training_modified.data","k3");
      Value v1 = DKV.get(ValueArray.makeVAKey(k1));
      Value v2 = DKV.get(ValueArray.makeVAKey(k2));
      Value v3 = DKV.get(ValueArray.makeVAKey(k3));
      assertTrue(v1.isBitIdentical(v2));
      assertTrue(v2.isBitIdentical(v1));
      assertFalse(v1.isBitIdentical(v3));
      assertFalse(v3.isBitIdentical(v2));
    } finally {
      if(k1 != null)UKV.remove(k1);
      if(k2 != null)UKV.remove(k2);
      if(k3 != null)UKV.remove(k3);
    }
  }
}
