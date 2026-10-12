package io.github.jiyuuchc.biopb.fiji;

import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Entry point: Plugins &gt; BioPB &gt; Tensor Browser. */
@Plugin(type = Command.class, menuPath = "Plugins>BioPB>Tensor Browser")
public class TensorBrowserCommand implements Command {

    @Parameter
    private LogService log;

    @Override
    public void run() {
        log.info("biopb Tensor Browser: not implemented yet");
    }
}
