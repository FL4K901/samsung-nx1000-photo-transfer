package com.nx.mobilelink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoAdapter extends BaseAdapter {
    private static final String TAG = "PhotoAdapter";

    public interface OnSelectionChangeListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final Context context;
    private final List<PhotoItem> items;
    private final LayoutInflater inflater;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService imageThreadPool;
    private final Handler mainHandler;
    private OnSelectionChangeListener selectionChangeListener;

    public PhotoAdapter(Context context, List<PhotoItem> items) {
        this.context = context;
        this.items = items;
        this.inflater = LayoutInflater.from(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.imageThreadPool = Executors.newFixedThreadPool(2);

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        this.memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public PhotoItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static class ViewHolder {
        ImageView imgThumbnail;
        CheckBox chkSelect;
        TextView badgeVideo;
        TextView txtTitle;
        TextView txtInfo;
        TextView txtStatus;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_photo, parent, false);
            holder = new ViewHolder();
            holder.imgThumbnail = convertView.findViewById(R.id.img_thumbnail);
            holder.chkSelect = convertView.findViewById(R.id.chk_photo_select);
            holder.badgeVideo = convertView.findViewById(R.id.badge_video);
            holder.txtTitle = convertView.findViewById(R.id.txt_photo_title);
            holder.txtInfo = convertView.findViewById(R.id.txt_photo_info);
            holder.txtStatus = convertView.findViewById(R.id.txt_photo_status);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final PhotoItem item = getItem(position);

        holder.txtTitle.setText(item.getTitle() != null ? item.getTitle() : "Photo " + position);
        
        StringBuilder info = new StringBuilder();
        if (item.getFormattedSize() != null && !item.getFormattedSize().isEmpty()) {
            info.append(item.getFormattedSize());
        }
        if (item.getDate() != null && !item.getDate().isEmpty()) {
            if (info.length() > 0) info.append(" | ");
            String d = item.getDate();
            if (d.length() > 10) d = d.substring(0, 10);
            info.append(d);
        }
        holder.txtInfo.setText(info.toString());

        holder.badgeVideo.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

        // Status
        if (item.getDownloadStatus() == PhotoItem.STATUS_COMPLETED) {
            holder.txtStatus.setVisibility(View.VISIBLE);
            holder.txtStatus.setText("✓ הורד בהצלחה");
            holder.txtStatus.setTextColor(0xFF30D158);
        } else if (item.getDownloadStatus() == PhotoItem.STATUS_DOWNLOADING) {
            holder.txtStatus.setVisibility(View.VISIBLE);
            holder.txtStatus.setText("מוריד...");
            holder.txtStatus.setTextColor(0xFFFF9F0A);
        } else if (item.getDownloadStatus() == PhotoItem.STATUS_ERROR) {
            holder.txtStatus.setVisibility(View.VISIBLE);
            holder.txtStatus.setText("שגיאה בהורדה");
            holder.txtStatus.setTextColor(0xFFFF453A);
        } else {
            holder.txtStatus.setVisibility(View.GONE);
        }

        // CheckBox
        holder.chkSelect.setOnCheckedChangeListener(null);
        holder.chkSelect.setChecked(item.isSelected());
        holder.chkSelect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                item.setSelected(isChecked);
                notifySelectionChanged();
            }
        });

        // Thumbnail loading
        final String thumbUrl = item.getThumbUrl();
        holder.imgThumbnail.setImageResource(R.drawable.thumb_placeholder);
        holder.imgThumbnail.setTag(thumbUrl);

        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Bitmap cached = memoryCache.get(thumbUrl);
            if (cached != null) {
                holder.imgThumbnail.setImageBitmap(cached);
            } else {
                imageThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            URL url = new URL(thumbUrl);
                            HttpURLConnection conn = MobileLinkClient.openConnection(url);
                            conn.setConnectTimeout(4000);
                            conn.setReadTimeout(5000);
                            conn.setRequestProperty("User-Agent", MobileLinkClient.getUserAgent());
                            conn.setRequestProperty("Connection", "close");

                            if (conn.getResponseCode() == 200) {
                                InputStream in = conn.getInputStream();
                                final Bitmap bmp = BitmapFactory.decodeStream(in);
                                in.close();
                                conn.disconnect();

                                if (bmp != null) {
                                    memoryCache.put(thumbUrl, bmp);
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (thumbUrl.equals(holder.imgThumbnail.getTag())) {
                                                holder.imgThumbnail.setImageBitmap(bmp);
                                            }
                                        }
                                    });
                                }
                            } else {
                                conn.disconnect();
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "Error loading thumb: " + thumbUrl);
                        }
                    }
                });
            }
        }

        return convertView;
    }

    public void selectAll(boolean select) {
        for (PhotoItem item : items) {
            item.setSelected(select);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public int getSelectedCount() {
        int count = 0;
        for (PhotoItem item : items) {
            if (item.isSelected()) count++;
        }
        return count;
    }

    private void notifySelectionChanged() {
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(getSelectedCount(), items.size());
        }
    }
}
