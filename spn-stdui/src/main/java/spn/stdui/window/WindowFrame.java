package spn.stdui.window;

import spn.stdui.action.ActionRegistry;
import spn.stdui.buffer.BufferRegistry;
import spn.stdui.input.InputEvent;
import spn.stdui.mode.ModeContext;
import spn.stdui.mode.ModeManager;
import spn.stdui.render.Renderer;
import spn.stdui.widget.Hud;

/**
 * A single window's logical state: owns a {@link ModeManager}, a {@link Hud},
 * and the shared {@link ModeContext}.
 *
 * <p>Does NOT own the platform window handle — that stays in the host
 * application (e.g., spn-gui's EditorWindow wraps GLFW).
 */
public class WindowFrame {

    private final ModeManager modeManager;
    private final Hud hud;
    private final ModeContext context;

    public WindowFrame(BufferRegistry buffers, Clipboard clipboard, ActionRegistry actions) {
        this.hud = new Hud();
        // ModeContext references itself via modeManager — create manager after context
        ModeManager[] mm = new ModeManager[1];
        this.context = new ModeContext(buffers, clipboard, actions, hud, null);
        this.modeManager = new ModeManager(
                new ModeContext(buffers, clipboard, actions, hud, null));
        // Rebuild context with the real mode manager
        ModeContext realCtx = new ModeContext(buffers, clipboard, actions, hud, modeManager);
        // Re-create modeManager with real context
        this.modeManager.setContext(realCtx);
    }

    /** Dispatch an input event through the mode manager. */
    public void dispatch(InputEvent event) {
        modeManager.dispatch(event);
    }

    /** Render: active mode content + HUD at the bottom. */
    public void render(Renderer renderer, float width, float height, double now) {
        float hudH = hud.preferredHeight(renderer);
        float contentH = height - hudH;

        modeManager.render(renderer, width, contentH, now);

        hud.setSegments(modeManager.hudSegments());
        hud.setBounds(0, contentH, width, hudH);
        hud.render(renderer, now);
    }

    public ModeManager getModeManager() { return modeManager; }
    public Hud getHud() { return hud; }
    public ModeContext getContext() { return context; }
}
