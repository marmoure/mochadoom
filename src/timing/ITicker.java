package timing;

import doom.CVarManager;
import doom.CommandVariable;
import doom.SourceCode.I_IBM;
import static doom.SourceCode.I_IBM.*;

public interface ITicker {

    static ITicker createTicker(CVarManager CVM) {
        int fps = CVM.get(CommandVariable.FPS, Integer.class, 0).orElse(60);
        if (CVM.bool(CommandVariable.MILLIS)) {
            return new MilliTicker(fps);
        } else if (CVM.bool(CommandVariable.FASTTIC) || CVM.bool(CommandVariable.FASTDEMO)) {
            return new DelegateTicker();
        } else {
            return new NanoTicker(fps);
        }
    }
    
    @I_IBM.C(I_GetTime)
    public int GetTime();
}