package com.eveningoutpost.dexdrip;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.RemoteViews;

import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError.Log;
import com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.BgSparklineBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.ColorCache;
import com.eveningoutpost.dexdrip.calibrations.PluggableCalibration;

import java.util.Date;
import java.util.List;


/**
 * Implementation of App Widget functionality.
 */
public class xDripWidget extends AppWidgetProvider {

    public static final String TAG = "xDripWidget";


    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        final PowerManager.WakeLock wl = JoH.getWakeLock("xdrip-widget-onupdate", 20000);
        final int N = appWidgetIds.length;
        for (int i = 0; i < N; i++) {

            //update the widget
            updateAppWidget(context, appWidgetManager, appWidgetIds[i]);

        }
        JoH.releaseWakeLock(wl);
    }

    @Override
    public void onEnabled(Context context) {
        Log.d(TAG, "Widget enabled");
        context.startService(new Intent(context, WidgetUpdateService.class));
    }

    @Override
    public void onDisabled(Context context) {
        Log.d(TAG, "Widget disabled");
        // Enter relevant functionality for when the last widget is disabled
    }

    private static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.x_drip_widget);
        Log.d(TAG, "Update widget signal received");

        //Add behaviour: open xDrip on click
        Intent intent = new Intent(context, Home.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
        views.setOnClickPendingIntent(R.id.xDripwidget, pendingIntent);
        displayCurrentInfo(appWidgetManager, appWidgetId, context, views);
        try {
            appWidgetManager.updateAppWidget(appWidgetId, views);
            // needed to catch RuntimeException and DeadObjectException
        } catch (Exception e) {
            Log.e(TAG, "Got Rexception in widget update: " + e);
        }
    }


    private static void displayCurrentInfo(AppWidgetManager appWidgetManager, int appWidgetId, Context context, RemoteViews views) {
        BgGraphBuilder bgGraphBuilder = new BgGraphBuilder(context);
        BgReading lastBgreading = BgReading.lastNoSenssor();

        final boolean showLines = Home.getPreferencesBoolean("widget_range_lines", false);
        final boolean showExstraStatus = Home.getPreferencesBoolean("extra_status_line", false) && Home.getPreferencesBoolean("widget_status_line", false);

        if (lastBgreading != null) {
            double estimate = 0;
            double estimated_delta = -9999;
            try {
                int height = appWidgetManager.getAppWidgetOptions(appWidgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                int width = appWidgetManager.getAppWidgetOptions(appWidgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
                views.setImageViewBitmap(R.id.widgetGraph, new BgSparklineBuilder(context)
                        .setBgGraphBuilder(bgGraphBuilder)
                        //.setShowFiltered(Home.getPreferencesBooleanDefaultFalse("show_filtered_curve"))
                        .setBackgroundColor(ColorCache.getCol(ColorCache.X.color_widget_chart_background))
                        .setHeight(height).setWidth(width).showHighLine(showLines).showLowLine(showLines).build());

                estimate = lastBgreading.calculated_value;
                String extrastring = "";
                String slope_arrow = lastBgreading.slopeArrow();
                String stringEstimate;

                // TODO use BestGlucose
                if ((BgGraphBuilder.last_noise > BgGraphBuilder.NOISE_TRIGGER)
                        && (BgGraphBuilder.best_bg_estimate > 0)
                        && (BgGraphBuilder.last_bg_estimate > 0)
                        && (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("bg_compensate_noise", false))) {
                    estimate = BgGraphBuilder.best_bg_estimate; // this needs scaling based on noise intensity
                    estimated_delta = BgGraphBuilder.best_bg_estimate - BgGraphBuilder.last_bg_estimate;
                    slope_arrow = BgReading.slopeToArrowSymbol(estimated_delta / (BgGraphBuilder.DEXCOM_PERIOD / 60000)); // delta by minute
                    //currentBgValueText.setTypeface(null, Typeface.ITALIC);
                    extrastring = " \u26A0"; // warning symbol !

                }
                // TODO functionize this check as it is in multiple places
                if (Home.getPreferencesBooleanDefaultFalse("display_glucose_from_plugin") && (PluggableCalibration.getCalibrationPluginFromPreferences() != null)){
                    extrastring += " "+context.getString(R.string.p_in_circle);
                }


                if ((new Date().getTime()) - Home.stale_data_millis() - lastBgreading.timestamp > 0) {
//                estimate = lastBgreading.calculated_value;
                    Log.d(TAG, "old value, estimate " + estimate);
                    stringEstimate = bgGraphBuilder.unitized_string(estimate);

                    //views.setTextViewText(R.id.widgetArrow, "--");
                    slope_arrow = "--";
                    views.setInt(R.id.widgetBg, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG);
                } else {
//                estimate = lastBgreading.calculated_value;
                    stringEstimate = bgGraphBuilder.unitized_string(estimate);

                    if (lastBgreading.hide_slope) {
                        slope_arrow = "--";
                    }
                    Log.d(TAG, "newish value, estimate " + stringEstimate + slope_arrow);


                    views.setInt(R.id.widgetBg, "setPaintFlags", 0);
                }
                views.setTextViewText(R.id.widgetBg, stringEstimate);
                views.setTextViewText(R.id.widgetArrow, slope_arrow);

                // is it really necessary to read this data once here and again in unitizedDeltaString?
                // couldn't we just use the unitizedDeltaString to detect the error condition?
                List<BgReading> bgReadingList = BgReading.latest(2, Home.get_follower());

                if (estimated_delta == -9999) {
                    // use original delta
                    if (bgReadingList != null && bgReadingList.size() == 2) {

                        views.setTextViewText(R.id.widgetDelta, bgGraphBuilder.unitizedDeltaString(true, true, Home.get_follower()));
                    } else {
                        views.setTextViewText(R.id.widgetDelta, "--");
                    }
                } else {
                    // use compensated estimate
                    views.setTextViewText(R.id.widgetDelta, bgGraphBuilder.unitizedDeltaStringRaw(true, true, estimated_delta));
                }

                int timeAgo = (int) Math.floor((new Date().getTime() - lastBgreading.timestamp) / (1000 * 60));
                if (timeAgo == 1) {
                    views.setTextViewText(R.id.readingAge, timeAgo + " Minute ago" + extrastring);
                } else {
                    views.setTextViewText(R.id.readingAge, timeAgo + " Minutes ago" + extrastring);
                }
                if (timeAgo > 15) {
                    views.setTextColor(R.id.readingAge, Color.parseColor("#FFBB33"));
                } else {
                    views.setTextColor(R.id.readingAge, Color.WHITE);
                }

                if(showExstraStatus) {
                    views.setTextViewText(R.id.widgetStatusLine, Home.extraStatusLine());
                    views.setViewVisibility(R.id.widgetStatusLine, View.VISIBLE);
                } else {
                    views.setTextViewText(R.id.widgetStatusLine, "");
                    views.setViewVisibility(R.id.widgetStatusLine, View.GONE);
                }
                if (bgGraphBuilder.unitized(estimate) <= bgGraphBuilder.lowMark) {
                    views.setTextColor(R.id.widgetBg, Color.parseColor("#C30909"));
                    views.setTextColor(R.id.widgetDelta, Color.parseColor("#C30909"));
                    views.setTextColor(R.id.widgetArrow, Color.parseColor("#C30909"));
                } else if (bgGraphBuilder.unitized(estimate) >= bgGraphBuilder.highMark) {
                    views.setTextColor(R.id.widgetBg, Color.parseColor("#FFBB33"));
                    views.setTextColor(R.id.widgetDelta, Color.parseColor("#FFBB33"));
                    views.setTextColor(R.id.widgetArrow, Color.parseColor("#FFBB33"));
                } else {
                    views.setTextColor(R.id.widgetBg, Color.WHITE);
                    views.setTextColor(R.id.widgetDelta, Color.WHITE);
                    views.setTextColor(R.id.widgetArrow, Color.WHITE);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Got exception in displaycurrentinfo: " + e);
            }
        }
    }
}


