package physicsbruhh;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PhysicsBruhh.MODID)
public class PhysicsBruhh {
    public static final String MODID = "physicsbruhh";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PhysicsBruhh(IEventBus modBus, ModContainer container) {
        LOGGER.info("Physics Bruhh loading");
    }
}
