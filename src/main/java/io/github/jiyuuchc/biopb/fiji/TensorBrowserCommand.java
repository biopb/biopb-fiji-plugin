package io.github.jiyuuchc.biopb.fiji;

import ij.IJ;
import java.awt.GraphicsEnvironment;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Entry point: Plugins &gt; BioPB &gt; Tensor Browser. Opens (or raises) the one browser window. */
@Plugin(type = Command.class, menuPath = "Plugins>BioPB>Tensor Browser")
public class TensorBrowserCommand implements Command {

    @Parameter
    private LogService log;

    @Override
    public void run() {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("biopb Tensor Browser needs a display; not available in headless mode");
            return;
        }
        TensorBrowserWindow.showLater(TensorBrowserPanel::new, t -> {
            log.error("biopb Tensor Browser could not start", t);
            IJ.handleException(t);
        });
    }
}
