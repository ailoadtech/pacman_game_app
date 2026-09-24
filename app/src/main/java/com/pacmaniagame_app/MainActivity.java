package com.pacmaniagame_app;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class MainActivity extends Activity {

    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        gameView = (GameView) findViewById(R.id.gameView);
        TextView scoreText = (TextView) findViewById(R.id.scoreText);
        TextView levelText = (TextView) findViewById(R.id.levelText);
        TextView livesText = (TextView) findViewById(R.id.livesText);
        gameView.setHudTextViews(scoreText, levelText, livesText);

        attachButton(R.id.btnLeft, GameView.DIR_LEFT);
        attachButton(R.id.btnRight, GameView.DIR_RIGHT);
        attachButton(R.id.btnUp, GameView.DIR_UP);
        attachButton(R.id.btnDown, GameView.DIR_DOWN);

        // Single-tap ghost icon next to the score toggles ghost mode.
        findViewById(R.id.ghostToggleBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gameView.toggleGhostMode();
            }
        });
    }

    private void attachButton(final int buttonId, final int dir) {
        View v = findViewById(buttonId);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        gameView.pressDirection(dir);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_POINTER_UP:
                        gameView.releaseDirection(dir);
                        break;
                }
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null) {
            gameView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.onPause();
        }
    }
}
