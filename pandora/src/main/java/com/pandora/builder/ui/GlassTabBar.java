package com.pandora.builder.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.liquidglass.LiquidGlassView;
import com.pandora.builder.R;

import java.util.function.IntConsumer;

public final class GlassTabBar extends FrameLayout {
    private final LiquidGlassView Surface;
    private final View SelectionBase;
    private final LiquidGlassView Selection;
    private final ImageView[] Icons = new ImageView[2];
    private final TextView[] Labels = new TextView[2];
    private IntConsumer OnTabSelected;
    private ValueAnimator Animation;
    private int SelectedIndex;
    private int SlotWidth;

    public GlassTabBar(Context Context, AttributeSet Attributes) {
        super(Context, Attributes);
        setClipChildren(false);
        setClipToPadding(false);

        Surface = new LiquidGlassView(Context);
        Surface.setGlassTint(0xb81c1c1e);
        Surface.setCornerRadius(Dp(28));
        Surface.setEnableBackdropBlur(true);
        Surface.setEnablePressEffect(false);
        Surface.setEnableShadow(false);
        Surface.setElevation(0f);
        addView(Surface, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        SelectionBase = new View(Context);
        SelectionBase.setBackgroundResource(R.drawable.selected_tab);
        SelectionBase.setElevation(Dp(2));

        Selection = new LiquidGlassView(Context);
        Selection.setGlassTint(0x403a3a3c);
        Selection.setCornerRadius(Dp(24));
        Selection.setEnableBackdropBlur(false);
        Selection.setEnablePressEffect(false);
        Selection.setEnableShadow(false);
        Selection.setElevation(Dp(1));
        Selection.setBackdropSource(Surface);
        addView(Selection, new LayoutParams(0, 0, Gravity.TOP | Gravity.START));
        addView(SelectionBase, new LayoutParams(0, 0, Gravity.TOP | Gravity.START));

        LinearLayout Items = new LinearLayout(Context);
        Items.setOrientation(LinearLayout.HORIZONTAL);
        Items.setElevation(Dp(3));
        addView(Items, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        AddTab(Items, 0, "Build", R.drawable.ic_folder);
        AddTab(Items, 1, "About", R.drawable.ic_info);
        UpdateColors();
    }

    public void SetBackdropSource(View Source) {
        Surface.setBackdropSource(Source);
    }

    public void SetOnTabSelected(IntConsumer Listener) {
        OnTabSelected = Listener;
    }

    public int GetSelectedIndex() {
        return SelectedIndex;
    }

    public void SelectTab(int Index) {
        if (Index < 0 || Index >= Icons.length || Index == SelectedIndex) {
            return;
        }
        SelectedIndex = Index;
        UpdateColors();
        if (Animation != null) {
            Animation.cancel();
        }
        float Target = Index * SlotWidth;
        Animation = ValueAnimator.ofFloat(Selection.getTranslationX(), Target);
        Animation.setDuration(320);
        Animation.setInterpolator(new OvershootInterpolator(0.75f));
        Animation.addUpdateListener(Animator -> {
            float Position = (Float) Animator.getAnimatedValue();
            SelectionBase.setTranslationX(Position);
            Selection.setTranslationX(Position);
        });
        Animation.start();
        if (OnTabSelected != null) {
            OnTabSelected.accept(Index);
        }
    }

    private void AddTab(LinearLayout Items, int Index, String Title, int Icon) {
        LinearLayout Tab = new LinearLayout(getContext());
        Tab.setOrientation(LinearLayout.VERTICAL);
        Tab.setGravity(Gravity.CENTER);
        Tab.setContentDescription(Title + " tab");
        Tab.setClickable(true);
        Tab.setFocusable(true);

        ImageView Image = new ImageView(getContext());
        Image.setImageResource(Icon);
        Image.setContentDescription(null);
        Tab.addView(Image, new LinearLayout.LayoutParams(Dp(19), Dp(19)));

        TextView Label = new TextView(getContext());
        Label.setText(Title);
        Label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        Label.setTypeface(getResources().getFont(R.font.inter_semibold));
        LinearLayout.LayoutParams LabelLayout = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        LabelLayout.topMargin = Dp(1);
        Tab.addView(Label, LabelLayout);

        Icons[Index] = Image;
        Labels[Index] = Label;
        Items.addView(Tab, new LinearLayout.LayoutParams(0,
                LayoutParams.MATCH_PARENT, 1f));
        Tab.setOnClickListener(View -> SelectTab(Index));
    }

    private void UpdateColors() {
        for (int Index = 0; Index < Icons.length; Index++) {
            int Color = getContext().getColor(
                    Index == SelectedIndex ? R.color.primary : R.color.text);
            Icons[Index].setImageTintList(ColorStateList.valueOf(Color));
            Labels[Index].setTextColor(Color);
        }
    }

    @Override
    protected void onMeasure(int WidthSpec, int HeightSpec) {
        int Width = MeasureSpec.getSize(WidthSpec);
        int Height = MeasureSpec.getSize(HeightSpec);
        int OuterInset = Dp(4);
        int InnerInset = Dp(12);
        int VerticalInset = Dp(7);
        SlotWidth = (Width - 2 * OuterInset) / 2;
        int IndicatorWidth = SlotWidth - 2 * InnerInset;
        int IndicatorHeight = Height - 2 * VerticalInset;
        SetIndicatorSize(Selection, IndicatorWidth, IndicatorHeight,
                OuterInset + InnerInset, VerticalInset);
        SetIndicatorSize(SelectionBase, IndicatorWidth, IndicatorHeight,
                OuterInset + InnerInset, VerticalInset);
        super.onMeasure(WidthSpec, HeightSpec);
    }

    @Override
    protected void onLayout(boolean Changed, int Left, int Top, int Right, int Bottom) {
        super.onLayout(Changed, Left, Top, Right, Bottom);
        if (Animation == null || !Animation.isRunning()) {
            float Position = SelectedIndex * SlotWidth;
            SelectionBase.setTranslationX(Position);
            Selection.setTranslationX(Position);
        }
    }

    private void SetIndicatorSize(View Indicator, int Width, int Height,
                                  int LeftInset, int TopInset) {
        LayoutParams Parameters = (LayoutParams) Indicator.getLayoutParams();
        Parameters.width = Width;
        Parameters.height = Height;
        Parameters.leftMargin = LeftInset;
        Parameters.topMargin = TopInset;
    }

    private int Dp(int Value) {
        return Math.round(Value * getResources().getDisplayMetrics().density);
    }
}
