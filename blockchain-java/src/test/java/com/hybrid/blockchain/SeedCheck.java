import com.hybrid.blockchain.*;
import java.math.BigInteger;

public class SeedCheck {
    public static void main(String[] args) {
        for (int i = 0; i <= 10; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            System.out.println("Seed " + i + ": " + kp.getAddress());
        }
        for (int i = 1; i <= 7; i++) {
            TestKeyPair kp = new TestKeyPair(i * 1000);
            System.out.println("Seed " + (i * 1000) + ": " + kp.getAddress());
        }
    }
}
