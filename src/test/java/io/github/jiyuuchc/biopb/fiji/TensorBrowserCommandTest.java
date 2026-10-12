package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.scijava.plugin.Plugin;

public class TensorBrowserCommandTest {

    @Test
    public void menuEntryIsUnderPluginsBioPB() {
        Plugin p = TensorBrowserCommand.class.getAnnotation(Plugin.class);
        assertEquals("Plugins>BioPB>Tensor Browser", p.menuPath());
    }
}
