package com.pacmaniagame_app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.Random;

public class GameView extends View {

    public static final int DIR_UP = 0;
    public static final int DIR_DOWN = 1;
    public static final int DIR_LEFT = 2;
    public static final int DIR_RIGHT = 3;

    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};

    private static final int WALL = 0;
    private static final int DOT = 1;
    private static final int POWER = 2;
    private static final int EMPTY = 3;
    private static final int DIAMOND = 4;

    private static final int STATE_READY = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_LEVEL_CLEAR = 3;
    private static final int STATE_GAME_OVER = 4;

    private static final int GHOST_NORMAL = 0;
    private static final int GHOST_FRIGHTENED = 1;
    private static final int GHOST_EATEN = 2;

    private final int colorWall = Color.rgb(0, 0, 255);
    private final int colorDot = Color.WHITE;
    private final int colorPac = Color.rgb(255, 228, 0);
    private final int colorDiamond = Color.rgb(255, 215, 0);
    private final int[] ghostColors = {
            Color.rgb(255, 0, 0),
            Color.rgb(255, 184, 222),
            Color.rgb(0, 255, 255),
            Color.rgb(255, 184, 82)
    };
    private final int frightColor = Color.rgb(33, 33, 222);

    private TextView scoreText;
    private TextView levelText;
    private TextView livesText;

    // Hidden cheat: tapping the Lives text three times sets lives to 99.
    private int livesTapCount = 0;
    private long lastLivesTapMs = 0L;
    private static final int LIVES_CHEAT_TAPS = 3;
    private static final long LIVES_CHEAT_WINDOW_MS = 1500L;
    private static final int LIVES_CHEAT_VALUE = 99;

    private int viewW;
    private int viewH;
    private int cols;
    private int rows;
    private int[][] tile;
    private int remainingDots;
    private Bitmap mazeLayer;
    private int T;
    private int ox;
    private int oy;

    private int level = 1;
    private int lives = 3;
    private int score = 0;
    private int state = STATE_READY;
    private float stateTime = 0f;

    private float frightTime = 0f;
    private float globalTime = 0f;

    private boolean[] pressed = new boolean[4];
    private int lastPressedDir = -1;
    private int steerDir = -1;
    private int facingDir = DIR_RIGHT;

    private boolean scatterMode = true;
    private float modeTimer = 8f;

    private boolean wrapH = false;
    private boolean wrapV = false;
    private int wrapRow = -1;
    private int wrapCol = -1;

    private Mob pac;
    private Mob[] ghosts;
    private int ghostCount;
    private boolean worldInitialized = false;
    private boolean running = false;
    private long lastFrameNs = 0L;
    private MediaPlayer music;

    private Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tempPath = new Path();
    private final RectF tempRect = new RectF();

    private static class Mob {
        float x;
        float y;
        int r;
        int c;
        int dir = -1;
        int tr;
        int tc;
        boolean moving = false;
        float speed;

        int ghostState = GHOST_NORMAL;
        int ghostId = 0;
        int homeR;
        int homeC;
        float freeze = 0f;

        Mob(int startR, int startC, float spd) {
            r = startR;
            c = startC;
            x = startC + 0.5f;
            y = startR + 0.5f;
            speed = spd;
        }
    }

    public GameView(Context context) {
        super(context);
        init();
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setHudTextViews(TextView sc, TextView lv, TextView li) {
        scoreText = sc;
        levelText = lv;
        livesText = li;
        if (livesText != null) {
            livesText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onLivesClicked();
                }
            });
        }
        updateHud();
    }

    /**
     * Cheat entry point: called when the Lives text in the HUD is tapped.
     * Three taps within a short window set the number of lives to 99.
     */
    private void onLivesClicked() {
        long now = System.currentTimeMillis();
        if (now - lastLivesTapMs > LIVES_CHEAT_WINDOW_MS) {
            livesTapCount = 0;
        }
        lastLivesTapMs = now;
        livesTapCount++;
        if (livesTapCount >= LIVES_CHEAT_TAPS) {
            livesTapCount = 0;
            lastLivesTapMs = 0L;
            activateCheatLives();
        }
    }

    private void activateCheatLives() {
        lives = LIVES_CHEAT_VALUE;
        if (state == STATE_GAME_OVER) {
            startGame();
            lives = LIVES_CHEAT_VALUE;
        }
        updateHud();
    }

    public void pressDirection(int dir) {
        facingDir = dir;
        pressed[dir] = true;
        lastPressedDir = dir;
        steerDir = dir;
    }

    public void releaseDirection(int dir) {
        pressed[dir] = false;
        if (lastPressedDir == dir) {
            lastPressedDir = -1;
        }
        if (steerDir == dir) {
            steerDir = -1;
            for (int d = 0; d < 4; d++) {
                if (pressed[d]) {
                    steerDir = d;
                }
            }
        }
    }

    public void onResume() {
        running = true;
        lastFrameNs = 0L;
        postInvalidateOnAnimation();
    }

    public void onPause() {
        running = false;
        pauseMusic();
    }

    private void startMusic() {
        if (getContext() == null) {
            return;
        }
        if (music == null) {
            try {
                music = MediaPlayer.create(getContext(), R.raw.pac_music);
            } catch (Exception e) {
                music = null;
            }
        }
        if (music != null) {
            try {
                music.setLooping(true);
                music.setVolume(0.45f, 0.45f);
                if (!music.isPlaying()) {
                    music.seekTo(0);
                    music.start();
                }
            } catch (Exception e) {
                // ignore audio errors
            }
        }
    }

    private void pauseMusic() {
        if (music != null) {
            try {
                if (music.isPlaying()) {
                    music.pause();
                }
            } catch (Exception e) {
                // ignore audio errors
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (music != null) {
            try {
                music.release();
            } catch (Exception e) {
                // ignore
            }
            music = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        computeGeometry();
        if (!worldInitialized && w > 0 && h > 0) {
            worldInitialized = true;
            startGame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        if (lastFrameNs == 0L) {
            lastFrameNs = now;
        }
        float dt = Math.min(0.05f, (now - lastFrameNs) / 1000000000.0f);
        lastFrameNs = now;
        globalTime += dt;

        if (!worldInitialized && getWidth() > 0 && getHeight() > 0) {
            viewW = getWidth();
            viewH = getHeight();
            computeGeometry();
            worldInitialized = true;
            startGame();
        }

        if (running) {
            if (mazeLayer == null) {
                rebuildLayer();
            }
            tick(dt);
            postInvalidateOnAnimation();
        }

        drawScene(canvas);
    }

    private void startGame() {
        score = 0;
        lives = 3;
        level = 1;
        steerDir = -1;
        initLevel();
        state = STATE_READY;
        stateTime = 0f;
        updateHud();
        invalidate();
    }

    private void computeGeometry() {
        if (cols > 0 && rows > 0 && viewW > 0 && viewH > 0) {
            T = Math.max(8, Math.min(viewW / cols, viewH / rows));
            ox = (viewW - cols * T) / 2;
            oy = (viewH - rows * T) / 2;
        }
    }

    private void initLevel() {
        ghostCount = Math.min(1 + level, 8);
        int cw = Math.min(6 + (level - 1), 16);
        int ch = Math.min(5 + (level - 1), 13);
        cols = cw * 2 + 1;
        rows = ch * 2 + 1;
        tile = new int[rows][cols];
        remainingDots = 0;
        rnd = new Random();

        wrapH = level >= 3;
        wrapV = level >= 6;

        genMaze();

        int hr = rows / 2;
        int hc = cols / 2;
        wrapRow = hr;
        wrapCol = hc;
        carveHorizontal(hr, hc, 9);
        carveVertical(hr, hc, 3);
        if (wrapH) {
            carveRowFull(hr);
        }
        if (wrapV) {
            carveColFull(hc);
        }

        int[] near = findNearestFloor(rows - 2, cols / 2);
        int pr = near[0];
        int pc = near[1];
        tile[pr][pc] = EMPTY;

        placePowerPellets(hr, hc);
        placeDiamonds(hr, hc, pr, pc);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (tile[r][c] == DOT || tile[r][c] == POWER) {
                    remainingDots++;
                }
            }
        }

        pac = new Mob(pr, pc, 6.2f);

        int[] offsets = {0, -1, 1, -2, 2, -3, 3, -4};
        ghosts = new Mob[ghostCount];
        for (int i = 0; i < ghostCount; i++) {
            int gr = hr;
            int gc = hc + offsets[i % offsets.length];
            gc = Math.max(1, Math.min(cols - 2, gc));
            Mob g = new Mob(gr, gc, ghostBaseSpeed(i));
            g.ghostId = i;
            g.homeR = gr;
            g.homeC = gc;
            g.freeze = 1.6f + i * 1.3f;
            ghosts[i] = g;
        }

        scatterMode = true;
        modeTimer = 9f;
        computeGeometry();
        frightTime = 0f;
        updateHud();
    }

    private float ghostBaseSpeed(int id) {
        return 1.7f + Math.min(0.10f * (level - 1), 1.2f) + Math.min(id * 0.04f, 0.2f);
    }

    private void genMaze() {
        int cellRows = (rows - 1) / 2;
        int cellCols = (cols - 1) / 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                tile[r][c] = WALL;
            }
        }
        int[][] stack = new int[rows * cols][2];
        boolean[][] visited = new boolean[rows][cols];
        int top = 0;
        int cr = 0;
        int cc = 0;
        tile[1][1] = DOT;
        visited[cr][cc] = true;
        stack[top][0] = cr;
        stack[top][1] = cc;
        top++;
        while (top > 0) {
            top--;
            cr = stack[top][0];
            cc = stack[top][1];
            int tr = 1 + cr * 2;
            int tc = 1 + cc * 2;
            int[] order = rndOrder(4);
            boolean moved = false;
            for (int i = 0; i < 4; i++) {
                int d = order[i];
                int nr = cr + DR[d];
                int nc = cc + DC[d];
                if (nr < 0 || nc < 0 || nr >= cellRows || nc >= cellCols || visited[nr][nc]) {
                    continue;
                }
                tile[tr + DR[d]][tc + DC[d]] = DOT;
                tile[tr + DR[d] * 2][tc + DC[d] * 2] = DOT;
                visited[nr][nc] = true;
                stack[top][0] = nr;
                stack[top][1] = nc;
                top++;
                moved = true;
                break;
            }
            if (moved) {
                stack[top][0] = cr;
                stack[top][1] = cc;
                top++;
            }
        }

        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < cols - 1; c++) {
                if (tile[r][c] != WALL) {
                    continue;
                }
                boolean lr = tile[r][c - 1] != WALL && tile[r][c + 1] != WALL;
                boolean ud = tile[r - 1][c] != WALL && tile[r + 1][c] != WALL;
                if ((lr || ud) && rnd.nextInt(100) < 14) {
                    tile[r][c] = DOT;
                }
            }
        }
    }

    private int[] rndOrder(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
        return a;
    }

    private void carveHorizontal(int r, int center, int width) {
        int half = (width - 1) / 2;
        for (int c = center - half; c <= center + half; c++) {
            if (r >= 1 && r < rows - 1 && c >= 1 && c < cols - 1) {
                tile[r][c] = EMPTY;
            }
        }
    }

    private void carveVertical(int center, int c, int height) {
        int half = (height - 1) / 2;
        for (int r = center - half; r <= center + half; r++) {
            if (r >= 1 && r < rows - 1 && c >= 1 && c < cols - 1) {
                tile[r][c] = EMPTY;
            }
        }
    }

    private void carveRowFull(int r) {
        for (int c = 0; c < cols; c++) {
            tile[r][c] = EMPTY;
        }
    }

    private void carveColFull(int c) {
        for (int r = 0; r < rows; r++) {
            tile[r][c] = EMPTY;
        }
    }

    private int[] findNearestFloor(int targetR, int targetC) {
        for (int radius = 0; radius <= Math.max(rows, cols); radius++) {
            for (int dr = -radius; dr <= radius; dr++) {
                for (int dc = -radius; dc <= radius; dc++) {
                    if (Math.max(Math.abs(dr), Math.abs(dc)) != radius) {
                        continue;
                    }
                    int r = targetR + dr;
                    int c = targetC + dc;
                    if (r >= 1 && r < rows - 1 && c >= 1 && c < cols - 1 && tile[r][c] != WALL) {
                        return new int[]{r, c};
                    }
                }
            }
        }
        return new int[]{1, 1};
    }

    private void placePowerPellets(int hr, int hc) {
        int[][] corners = {
                {1, 1},
                {rows - 2, 1},
                {1, cols - 2},
                {rows - 2, cols - 2}
        };
        for (int i = 0; i < corners.length; i++) {
            int[] near = findNearestFloor(corners[i][0], corners[i][1]);
            int r = near[0];
            int c = near[1];
            if (r == hr && c == hc) {
                continue;
            }
            if (Math.abs(r - hr) + Math.abs(c - hc) < 3) {
                continue;
            }
            if (tile[r][c] == DOT) {
                tile[r][c] = POWER;
            }
        }
    }

    /**
     * Scatters a few yellow diamonds around the maze. Each one eaten by Pac-Man
     * grants an extra life. They are placed on open floor tiles, away from the
     * ghost pen and the Pac-Man start position.
     */
    private void placeDiamonds(int hr, int hc, int pacR, int pacC) {
        int count = 3 + Math.min(level - 1, 3);
        int placed = 0;
        int guard = 0;
        while (placed < count && guard < 600) {
            guard++;
            int r = 1 + rnd.nextInt(Math.max(1, rows - 2));
            int c = 1 + rnd.nextInt(Math.max(1, cols - 2));
            if (tile[r][c] != DOT) {
                continue;
            }
            if (Math.abs(r - hr) + Math.abs(c - hc) < 4) {
                continue;
            }
            if (Math.abs(r - pacR) + Math.abs(c - pacC) < 3) {
                continue;
            }
            boolean tooClose = false;
            for (int dr = -2; dr <= 2 && !tooClose; dr++) {
                for (int dc = -2; dc <= 2 && !tooClose; dc++) {
                    int rr = r + dr;
                    int cc = c + dc;
                    if (rr >= 0 && rr < rows && cc >= 0 && cc < cols && tile[rr][cc] == DIAMOND) {
                        tooClose = true;
                    }
                }
            }
            if (tooClose) {
                continue;
            }
            tile[r][c] = DIAMOND;
            placed++;
        }
    }

    private void tick(float dt) {
        if (state == STATE_PLAYING) {
            startMusic();
        } else {
            pauseMusic();
        }
        switch (state) {
            case STATE_READY:
                stateTime += dt;
                if (stateTime >= 1.8f) {
                    state = STATE_PLAYING;
                    stateTime = 0f;
                }
                break;
            case STATE_PLAYING:
                updateGame(dt);
                break;
            case STATE_DYING:
                stateTime += dt;
                if (stateTime >= 1.6f) {
                    if (lives <= 0) {
                        state = STATE_GAME_OVER;
                        stateTime = 0f;
                    } else {
                        resetPositions();
                        state = STATE_PLAYING;
                        stateTime = 0f;
                    }
                }
                break;
            case STATE_LEVEL_CLEAR:
                stateTime += dt;
                if (stateTime >= 2.0f) {
                    level++;
                    steerDir = -1;
                    initLevel();
                    state = STATE_READY;
                    stateTime = 0f;
                }
                break;
            case STATE_GAME_OVER:
                break;
        }
    }

    private void updateGame(float dt) {
        for (int i = 0; i < ghostCount; i++) {
            Mob g = ghosts[i];
            if (g.freeze > 0f) {
                g.freeze -= dt;
                if (g.freeze < 0f) {
                    g.freeze = 0f;
                }
            }
        }

        modeTimer -= dt;
        if (modeTimer <= 0f) {
            scatterMode = !scatterMode;
            modeTimer = scatterMode ? 5f : 10f;
        }

        if (frightTime > 0f) {
            frightTime -= dt;
            if (frightTime <= 0f) {
                frightTime = 0f;
                for (int i = 0; i < ghostCount; i++) {
                    Mob g = ghosts[i];
                    if (g.ghostState == GHOST_FRIGHTENED) {
                        g.ghostState = GHOST_NORMAL;
                    }
                }
            }
        }

        updateMob(pac, dt);
        for (int i = 0; i < ghostCount; i++) {
            updateMob(ghosts[i], dt);
        }

        handleCollisions();

        if (remainingDots <= 0 && state == STATE_PLAYING) {
            score += 1000 + level * 100;
            state = STATE_LEVEL_CLEAR;
            stateTime = 0f;
            updateHud();
        }
    }

    private void updateMob(Mob m, float dt) {
        if (m == pac) {
            if (m.moving) {
                advance(m, dt, m.speed);
            } else {
                pacArrive();
            }
        } else {
            if (m.freeze > 0f) {
                return;
            }
            if (m.moving) {
                advance(m, dt, effectiveGhostSpeed(m));
            } else {
                ghostArrive(m);
            }
        }
    }

    private float effectiveGhostSpeed(Mob g) {
        if (g.ghostState == GHOST_EATEN) {
            return 8.0f;
        }
        if (g.ghostState == GHOST_FRIGHTENED) {
            return g.speed * 0.45f;
        }
        return g.speed;
    }

    private void advance(Mob m, float dt, float speed) {
        if (Math.abs(m.tc - m.c) > 1 || Math.abs(m.tr - m.r) > 1) {
            m.c = m.tc;
            m.r = m.tr;
            m.x = m.c + 0.5f;
            m.y = m.r + 0.5f;
            m.moving = false;
            if (m == pac) {
                pacArrive();
            } else {
                ghostArrive(m);
            }
            return;
        }
        float tx = m.tc + 0.5f;
        float ty = m.tr + 0.5f;
        float dist = Math.abs(m.x - tx) + Math.abs(m.y - ty);
        float step = speed * dt;
        if (step >= dist) {
            m.x = tx;
            m.y = ty;
            m.r = m.tr;
            m.c = m.tc;
            m.moving = false;
            if (m == pac) {
                pacArrive();
            } else {
                ghostArrive(m);
            }
        } else {
            m.x += DC[m.dir] * step;
            m.y += DR[m.dir] * step;
        }
    }

    private void pacArrive() {
        if (state != STATE_PLAYING) {
            return;
        }
        eatTileAt(pac.r, pac.c);
        int want = steerDir;
        if (want >= 0 && openCell(pac.r, pac.c, want)) {
            startMove(pac, want);
        } else {
            pac.dir = -1;
            pac.moving = false;
        }
    }

    private void eatTileAt(int r, int c) {
        int t = tile[r][c];
        if (t == DOT) {
            tile[r][c] = EMPTY;
            remainingDots--;
            score += 10;
            rebuildLayer();
            updateHud();
        } else if (t == DIAMOND) {
            tile[r][c] = EMPTY;
            score += 100;
            lives++;
            rebuildLayer();
            updateHud();
        } else if (t == POWER) {
            tile[r][c] = EMPTY;
            remainingDots--;
            score += 50;
            frightTime = 7.0f;
            for (int i = 0; i < ghostCount; i++) {
                Mob g = ghosts[i];
                if (g.ghostState != GHOST_EATEN && g.freeze <= 0f) {
                    g.ghostState = GHOST_FRIGHTENED;
                }
            }
            rebuildLayer();
            updateHud();
        }
    }

    private int[] stepCell(int r, int c, int dir) {
        int nr = r + DR[dir];
        int nc = c + DC[dir];
        if (dir == DIR_LEFT && c == 0 && wrapH && r == wrapRow) {
            return new int[]{r, cols - 1};
        }
        if (dir == DIR_RIGHT && c == cols - 1 && wrapH && r == wrapRow) {
            return new int[]{r, 0};
        }
        if (dir == DIR_UP && r == 0 && wrapV && c == wrapCol) {
            return new int[]{rows - 1, c};
        }
        if (dir == DIR_DOWN && r == rows - 1 && wrapV && c == wrapCol) {
            return new int[]{0, c};
        }
        if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) {
            return null;
        }
        return new int[]{nr, nc};
    }

    private void startMove(Mob m, int dir) {
        int[] nb = stepCell(m.r, m.c, dir);
        if (nb == null) {
            m.moving = false;
            return;
        }
        m.dir = dir;
        m.tr = nb[0];
        m.tc = nb[1];
        m.moving = true;
    }

    private boolean openCell(int r, int c, int dir) {
        int[] nb = stepCell(r, c, dir);
        return nb != null && tile[nb[0]][nb[1]] != WALL;
    }

    private int[] scatterTarget(int id) {
        switch (id % 4) {
            case 0:
                return new int[]{1, cols - 2};
            case 1:
                return new int[]{1, 1};
            case 2:
                return new int[]{rows - 2, cols - 2};
            default:
                return new int[]{rows - 2, 1};
        }
    }

    private void ghostArrive(Mob g) {
        if (g.ghostState == GHOST_EATEN) {
            pickGhostDirection(g, g.homeR, g.homeC, true);
        } else if (g.ghostState == GHOST_FRIGHTENED) {
            pickGhostDirection(g, -1, -1, false);
        } else if (scatterMode) {
            int[] t = scatterTarget(g.ghostId);
            pickGhostDirection(g, t[0], t[1], true);
        } else {
            pickGhostDirection(g, pac.r, pac.c, true);
        }
    }

    private void pickGhostDirection(Mob g, int targetR, int targetC, boolean chase) {
        int rev = g.dir >= 0 ? (g.dir ^ 1) : -1;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        int revBest = -1;
        int revScore = Integer.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            int[] nb = stepCell(g.r, g.c, d);
            if (nb == null) {
                continue;
            }
            int nr = nb[0];
            int nc = nb[1];
            int s;
            if (chase) {
                s = Math.abs(nr - targetR) + Math.abs(nc - targetC);
            } else {
                s = -(Math.abs(nr - pac.r) + Math.abs(nc - pac.c));
            }
            if (d == rev) {
                if (s < revScore) {
                    revScore = s;
                    revBest = d;
                }
            } else if (s < bestScore) {
                bestScore = s;
                best = d;
            }
        }

        if (best == -1 && revBest != -1) {
            best = revBest;
        }

        if (best >= 0) {
            startMove(g, best);
        } else {
            g.dir = -1;
            g.moving = false;
        }

        if (g.ghostState == GHOST_EATEN && g.r == g.homeR && g.c == g.homeC) {
            g.ghostState = frightTime > 0f ? GHOST_FRIGHTENED : GHOST_NORMAL;
            g.moving = false;
            g.dir = -1;
            g.freeze = 0.3f;
        }
    }

    private void handleCollisions() {
        if (pac == null || state != STATE_PLAYING) {
            return;
        }
        for (int i = 0; i < ghostCount; i++) {
            Mob g = ghosts[i];
            if (g.ghostState == GHOST_EATEN) {
                continue;
            }
            if (g.freeze > 0f) {
                continue;
            }
            if (g.r == pac.r && g.c == pac.c) {
                if (frightTime > 0f) {
                    score += 200;
                    g.ghostState = GHOST_EATEN;
                    g.moving = false;
                    g.dir = -1;
                    updateHud();
                } else {
                    lives--;
                    updateHud();
                    state = STATE_DYING;
                    stateTime = 0f;
                    break;
                }
            }
        }
    }

    private void resetPositions() {
        steerDir = -1;
        int[] near = findNearestFloor(rows - 2, cols / 2);
        pac.r = near[0];
        pac.c = near[1];
        pac.x = pac.c + 0.5f;
        pac.y = pac.r + 0.5f;
        pac.moving = false;
        pac.dir = -1;

        for (int i = 0; i < ghostCount; i++) {
            Mob g = ghosts[i];
            g.r = g.homeR;
            g.c = g.homeC;
            g.x = g.c + 0.5f;
            g.y = g.r + 0.5f;
            g.moving = false;
            g.dir = -1;
            g.ghostState = GHOST_NORMAL;
            g.freeze = 1.2f + i * 0.9f;
        }
        scatterMode = true;
        modeTimer = 7f;
        frightTime = 0f;
    }

    private void rebuildLayer() {
        if (cols <= 0 || rows <= 0 || T <= 0) {
            return;
        }
        if (mazeLayer != null && !mazeLayer.isRecycled()) {
            mazeLayer.recycle();
        }
        mazeLayer = Bitmap.createBitmap(cols * T, rows * T, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(mazeLayer);
        cv.drawColor(Color.BLACK);

        float th = Math.max(1f, T * 0.20f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(th);
        paint.setColor(colorWall);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (tile[r][c] == WALL) {
                    continue;
                }
                float px = c * T;
                float py = r * T;
                if (r > 0 && tile[r - 1][c] == WALL) {
                    cv.drawLine(px, py, px + T, py, paint);
                }
                if (r < rows - 1 && tile[r + 1][c] == WALL) {
                    cv.drawLine(px, py + T, px + T, py + T, paint);
                }
                if (c > 0 && tile[r][c - 1] == WALL) {
                    cv.drawLine(px, py, px, py + T, paint);
                }
                if (c < cols - 1 && tile[r][c + 1] == WALL) {
                    cv.drawLine(px + T, py, px + T, py + T, paint);
                }
            }
        }

        paint.setStyle(Paint.Style.FILL);
        float dotR = Math.max(1.2f, T * 0.12f);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int t = tile[r][c];
                float px = c * T;
                float py = r * T;
                if (t == DOT) {
                    paint.setColor(colorDot);
                    cv.drawCircle(px + T / 2f, py + T / 2f, dotR, paint);
                } else if (t == DIAMOND) {
                    drawDiamondShape(cv, px + T / 2f, py + T / 2f, Math.max(4f, T * 0.26f));
                } else if (t == POWER) {
                    paint.setColor(colorDot);
                    float rad = Math.max(3f, T * 0.28f);
                    cv.drawCircle(px + T / 2f, py + T / 2f, rad, paint);
                }
            }
        }
    }

    private void drawDiamondShape(Canvas cv, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.FILL);
        tempPath.reset();
        tempPath.moveTo(cx, cy - s);
        tempPath.lineTo(cx + s * 0.78f, cy);
        tempPath.lineTo(cx, cy + s);
        tempPath.lineTo(cx - s * 0.78f, cy);
        tempPath.close();
        paint.setColor(colorDiamond);
        cv.drawPath(tempPath, paint);

        // simple facet lines for a gem look
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, s * 0.12f));
        int edge = Color.rgb(255, 244, 150);
        paint.setColor(edge);
        cv.drawLine(cx - s * 0.78f, cy, cx - s * 0.3f, cy - s * 0.45f, paint);
        cv.drawLine(cx + s * 0.78f, cy, cx + s * 0.3f, cy - s * 0.45f, paint);
        cv.drawLine(cx - s * 0.3f, cy - s * 0.45f, cx + s * 0.3f, cy - s * 0.45f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawScene(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (mazeLayer != null) {
            canvas.drawBitmap(mazeLayer, ox, oy, null);
        }

        if (worldInitialized && pac != null) {
            for (int i = 0; i < ghostCount; i++) {
                drawGhost(canvas, ghosts[i]);
            }
            if (state != STATE_GAME_OVER) {
                drawPacman(canvas);
            }
        }

        drawOverlays(canvas);
    }

    private void drawPacman(Canvas canvas) {
        float cx = ox + pac.x * T;
        float cy = oy + pac.y * T;
        float radius = T * 0.34f;
        if (state == STATE_DYING) {
            float f = Math.min(1f, stateTime / 1.2f);
            radius = radius * (1f - f * 0.8f);
            paint.setColor(Color.rgb(255, 60, 60));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, Math.max(1f, radius), paint);
            return;
        }
        float angleDeg = 0f;
        int d = pac.dir >= 0 ? pac.dir : facingDir;
        switch (d) {
            case DIR_UP:
                angleDeg = 270f;
                break;
            case DIR_DOWN:
                angleDeg = 90f;
                break;
            case DIR_LEFT:
                angleDeg = 180f;
                break;
            case DIR_RIGHT:
            default:
                angleDeg = 0f;
                break;
        }
        float open;
        if (pac.moving) {
            open = 10f + 20f * Math.abs((float) Math.sin(globalTime * 9f));
        } else {
            open = 8f;
        }
        open = Math.min(open, 55f);
        paint.setColor(colorPac);
        paint.setStyle(Paint.Style.FILL);
        tempRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(tempRect, angleDeg + open, 360f - open * 2f, true, paint);
    }

    private void drawGhost(Canvas canvas, Mob g) {
        if (g == null) {
            return;
        }
        float cx = ox + g.x * T;
        float cy = oy + g.y * T;
        float R = T * 0.26f;
        boolean eaten = g.ghostState == GHOST_EATEN;
        if (eaten) {
            drawGhostEyes(canvas, cx, cy, R, g.dir, Color.WHITE);
            return;
        }

        boolean scared = g.ghostState == GHOST_FRIGHTENED && frightTime > 0f;
        boolean blinkWhite = false;
        if (scared && frightTime < 1.5f) {
            blinkWhite = ((int) (globalTime * 8f)) % 2 == 0;
        }

        int body = ghostColors[g.ghostId % ghostColors.length];
        if (scared) {
            body = blinkWhite ? Color.WHITE : frightColor;
        }

        float domeY = cy - R * 0.4f;
        float footR = R * 0.45f;

        tempPath.reset();
        tempPath.addCircle(cx, domeY, R, Path.Direction.CW);
        tempRect.set(cx - R, cy - R * 0.4f, cx + R, cy + R * 0.8f);
        tempPath.addRect(tempRect, Path.Direction.CW);
        tempPath.addCircle(cx - R * 0.66f, cy + R * 0.8f, footR, Path.Direction.CW);
        tempPath.addCircle(cx, cy + R * 0.8f, footR, Path.Direction.CW);
        tempPath.addCircle(cx + R * 0.66f, cy + R * 0.8f, footR, Path.Direction.CW);
        tempPath.setFillType(Path.FillType.WINDING);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(body);
        canvas.drawPath(tempPath, paint);

        if (scared) {
            paint.setColor(Color.WHITE);
            tempRect.set(cx - R * 0.55f, cy - R * 0.75f, cx + R * 0.55f, cy - R * 0.35f);
            canvas.drawOval(tempRect, paint);
            paint.setColor(Color.rgb(255, 130, 130));
            tempRect.set(cx - R * 0.55f, cy - R * 0.2f, cx + R * 0.55f, cy + R * 0.15f);
            canvas.drawOval(tempRect, paint);
        } else {
            drawGhostEyes(canvas, cx, cy, R, g.dir, body);
        }
    }

    private void drawGhostEyes(Canvas canvas, float cx, float cy, float R, int dir, int bodyColor) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        float domeY = cy - R * 0.4f;
        float eyeY = domeY - R * 0.2f;
        float eyeOff = R * 0.4f;
        float eyeR = R * 0.28f;
        canvas.drawCircle(cx - eyeOff, eyeY, eyeR, paint);
        canvas.drawCircle(cx + eyeOff, eyeY, eyeR, paint);

        float dx = 0f;
        float dy = 0f;
        switch (dir) {
            case DIR_UP:
                dy = -eyeR * 0.45f;
                break;
            case DIR_DOWN:
                dy = eyeR * 0.45f;
                break;
            case DIR_LEFT:
                dx = -eyeR * 0.45f;
                break;
            case DIR_RIGHT:
                dx = eyeR * 0.45f;
                break;
        }
        paint.setColor(bodyColor == Color.WHITE ? Color.BLUE : Color.rgb(33, 33, 222));
        float pr = eyeR * 0.5f;
        canvas.drawCircle(cx - eyeOff + dx, eyeY + dy, pr, paint);
        canvas.drawCircle(cx + eyeOff + dx, eyeY + dy, pr, paint);
    }

    private void drawOverlays(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float cx = viewW / 2f;
        float cy = viewH / 2f;

        switch (state) {
            case STATE_READY:
                textPaint.setColor(colorPac);
                textPaint.setTextSize(34f * density);
                canvas.drawText("READY!", cx, cy - 20f * density, textPaint);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(18f * density);
                canvas.drawText("Hold buttons to move, release to stop", cx, cy + 20f * density, textPaint);
                break;
            case STATE_DYING:
                textPaint.setColor(Color.rgb(255, 120, 120));
                textPaint.setTextSize(30f * density);
                canvas.drawText("OUCH!", cx, cy, textPaint);
                break;
            case STATE_LEVEL_CLEAR:
                textPaint.setColor(colorPac);
                textPaint.setTextSize(32f * density);
                canvas.drawText("LEVEL CLEAR!", cx, cy - 20f * density, textPaint);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(20f * density);
                canvas.drawText("Bonus +" + (1000 + level * 100), cx, cy + 24f * density, textPaint);
                break;
            case STATE_GAME_OVER:
                textPaint.setColor(Color.rgb(255, 80, 80));
                textPaint.setTextSize(44f * density);
                canvas.drawText("GAME OVER", cx, cy - 30f * density, textPaint);
                textPaint.setColor(colorPac);
                textPaint.setTextSize(22f * density);
                canvas.drawText("Final score: " + score, cx, cy + 12f * density, textPaint);
                canvas.drawText("Tap to play again", cx, cy + 48f * density, textPaint);
                break;
            default:
                break;
        }
    }

    private void updateHud() {
        if (scoreText != null) {
            scoreText.setText("Score: " + score);
        }
        if (levelText != null) {
            levelText.setText("Level " + level);
        }
        if (livesText != null) {
            livesText.setText("Lives: " + Math.max(0, lives));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state == STATE_GAME_OVER && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startGame();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
