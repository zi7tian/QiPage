package local.readapp.feature
import org.junit.Assert.*
import org.junit.Test
class ProgressLabelTest {
    @Test fun pageAndSingleDecimal(){assertEquals("9/15 14.2%",progressLabel(9,15,14.24));assertEquals("15/15 100.0%",progressLabel(18,15,101.0));assertEquals("1/1 0.0%",progressLabel(0,1,-1.0))}
}
