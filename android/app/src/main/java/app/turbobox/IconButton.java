package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.widget.TextView;

/**
 * A button that shows a drawn icon instead of words. It is a TextView so the rest of the code can still tint it and set its
 * description; the text itself is never drawn. Icons are drawn with paths on a 24 x 24 grid.
 */
final class IconButton extends TextView {
    enum Icon { PAUSE, PLAY, RECORD, BOTH, MIC, MIC_OFF, TIMER, GEAR, CLOSE, LAYOUT, MINI, LOCK, SPLIT, CAPTIONS, HIDE, STOP, UP, DOWN, EDIT, TRASH }

    private Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int iconColor = Ui.TEXT;

    IconButton(Context c, Icon icon, String description) {
        super(c);
        this.icon = icon;
        setContentDescription(description);
        setGravity(Gravity.CENTER);
        setClickable(true);
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);
        Ui.tint(c, this, 0);
        setOnLongClickListener(v -> {
            android.widget.Toast.makeText(c, description, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    void setIcon(Icon icon) {
        if (this.icon != icon) {
            this.icon = icon;
            invalidate();
        }
    }

    /** The icon's colour (white by default; red/lime for a state). */
    void setIconColor(int color) {
        iconColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Ui.dp(getContext(), 44);
        int width = MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : s;
        setMeasuredDimension(width, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight()) * 0.5f;
        float k = size / 24f;
        c.save();
        c.translate(getWidth() / 2f - size / 2f, getHeight() / 2f - size / 2f);
        c.scale(k, k);
        paint.setColor(isEnabled() ? iconColor : Ui.FAINT);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(2.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        draw(c, icon);
        c.restore();
    }

    private void bars(Canvas c, float x) {
        c.drawRoundRect(new RectF(x + 5, 4, x + 10, 20), 1.5f, 1.5f, paint);
        c.drawRoundRect(new RectF(x + 14, 4, x + 19, 20), 1.5f, 1.5f, paint);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        paint.setStyle(Paint.Style.STROKE);
        c.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void draw(Canvas c, Icon i) {
        path.reset();
        switch (i) {
            case PAUSE:
                bars(c, 0);
                break;
            case PLAY:
                path.moveTo(7, 4);
                path.lineTo(20, 12);
                path.lineTo(7, 20);
                path.close();
                c.drawPath(path, paint);
                break;
            case RECORD:
                paint.setColor(isEnabled() ? Ui.RED : Ui.FAINT);
                c.drawCircle(12, 12, 7, paint);
                break;
            case STOP:
                paint.setColor(isEnabled() ? Ui.RED : Ui.FAINT);
                c.drawRoundRect(new RectF(6, 6, 18, 18), 2f, 2f, paint);
                break;
            case BOTH: {
                // A record dot and a pause mark side by side.
                int col = paint.getColor();
                paint.setColor(Ui.RED);
                c.drawCircle(6.5f, 12, 4.5f, paint);
                paint.setColor(col);
                c.drawRoundRect(new RectF(13.5f, 5, 16.5f, 19), 1f, 1f, paint);
                c.drawRoundRect(new RectF(18.5f, 5, 21.5f, 19), 1f, 1f, paint);
                break;
            }
            case MIC:
            case MIC_OFF:
                c.drawRoundRect(new RectF(9, 3, 15, 14), 3f, 3f, paint);
                paint.setStyle(Paint.Style.STROKE);
                path.moveTo(6, 11);
                path.quadTo(6, 18, 12, 18);
                path.quadTo(18, 18, 18, 11);
                c.drawPath(path, paint);
                c.drawLine(12, 18, 12, 21, paint);
                paint.setStyle(Paint.Style.FILL);
                if (i == Icon.MIC_OFF) {
                    paint.setColor(Ui.RED);
                    paint.setStrokeWidth(2.6f);
                    line(c, 4, 4, 20, 20);
                }
                break;
            case TIMER:
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(12, 13, 8, paint);
                c.drawLine(12, 13, 12, 8, paint);
                c.drawLine(12, 13, 15, 15, paint);
                c.drawLine(9, 2.5f, 15, 2.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case GEAR:
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(12, 12, 3.2f, paint);
                for (int a = 0; a < 8; a++) {
                    double r = Math.toRadians(a * 45);
                    c.drawLine((float) (12 + Math.cos(r) * 6.5), (float) (12 + Math.sin(r) * 6.5),
                            (float) (12 + Math.cos(r) * 9.5), (float) (12 + Math.sin(r) * 9.5), paint);
                }
                c.drawCircle(12, 12, 6.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case CLOSE:
                paint.setColor(isEnabled() ? Ui.RED : Ui.FAINT);
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case LAYOUT:
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3, 4, 21, 20), 2.5f, 2.5f, paint);
                c.drawLine(10, 4, 10, 20, paint);
                c.drawLine(10, 12, 21, 12, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case SPLIT:
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3, 4, 21, 20), 2.5f, 2.5f, paint);
                c.drawLine(12, 4, 12, 20, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case MINI:
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3, 4, 21, 20), 2.5f, 2.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(12, 11, 19, 17), 1.5f, 1.5f, paint);
                break;
            case LOCK:
                c.drawRoundRect(new RectF(5, 11, 19, 21), 2.5f, 2.5f, paint);
                paint.setStyle(Paint.Style.STROKE);
                path.moveTo(8, 11);
                path.lineTo(8, 8);
                path.quadTo(8, 3.5f, 12, 3.5f);
                path.quadTo(16, 3.5f, 16, 8);
                path.lineTo(16, 11);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case CAPTIONS:
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3, 5, 21, 19), 3f, 3f, paint);
                c.drawLine(7, 11, 11, 11, paint);
                c.drawLine(13, 11, 17, 11, paint);
                c.drawLine(7, 15, 14, 15, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case UP:
            case DOWN: {
                float a = i == Icon.UP ? 15 : 9, b = i == Icon.UP ? 9 : 15;
                path.moveTo(5, a);
                path.lineTo(12, b);
                path.lineTo(19, a);
                paint.setStyle(Paint.Style.STROKE);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case EDIT:
                path.moveTo(4, 20);
                path.lineTo(5, 15);
                path.lineTo(16, 4);
                path.lineTo(20, 8);
                path.lineTo(9, 19);
                path.close();
                paint.setStyle(Paint.Style.STROKE);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case TRASH:
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(4, 7, 20, 7, paint);
                c.drawRoundRect(new RectF(6.5f, 7, 17.5f, 20.5f), 2f, 2f, paint);
                c.drawLine(9.5f, 4, 14.5f, 4, paint);
                c.drawLine(10, 11, 10, 16, paint);
                c.drawLine(14, 11, 14, 16, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case HIDE:
                path.moveTo(5, 9);
                path.lineTo(12, 16);
                path.lineTo(19, 9);
                paint.setStyle(Paint.Style.STROKE);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            default:
                break;
        }
    }
}
