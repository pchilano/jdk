/*
 * @test exception02
 * @library /testlibrary /test/lib
 * @run main/othervm/native -agentlib:exception02 -Xint -XX:+UseZGC exception02
 */

public class exception02 {

    static {
        System.loadLibrary("exception02");
    }

    native static int enableEvent();

    public static void main(String args[]) {
        int result = enableEvent();
        if (result != 0) {
            throw new RuntimeException("enableEvent failed with result " + result);
        }
        exception02 test = new exception02();
        test.run();
    }

    private void run() {
        try {
            while (true) {
                recurse(10);
            }
        } catch (Exception e) {}
    }

    private void recurse(int depth) {
        if (depth > 0) {
            recurse(depth - 1);
        }
        throw new RuntimeException("throwing exception at recursion end!");
    }
}
