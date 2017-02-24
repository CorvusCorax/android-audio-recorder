package com.github.axet.audiolibrary.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.axet.androidlibrary.animations.RemoveItemAnimation;
import com.github.axet.androidlibrary.app.MainLibrary;
import com.github.axet.androidlibrary.widgets.OpenFileDialog;
import com.github.axet.androidlibrary.widgets.PopupShareActionProvider;
import com.github.axet.audiolibrary.R;
import com.github.axet.audiolibrary.animations.RecordingAnimation;
import com.github.axet.audiolibrary.encoders.Factory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Recordings extends ArrayAdapter<File> implements AbsListView.OnScrollListener {
    public static String TAG = Recordings.class.getSimpleName();

    static final int TYPE_COLLAPSED = 0;
    static final int TYPE_EXPANDED = 1;
    static final int TYPE_DELETED = 2;

    public static class SortFiles implements Comparator<File> {
        @Override
        public int compare(File file, File file2) {
            if (file.isDirectory() && file2.isFile())
                return -1;
            else if (file.isFile() && file2.isDirectory())
                return 1;
            else
                return file.getPath().compareTo(file2.getPath());
        }
    }

    Handler handler;
    Storage storage;
    MediaPlayer player;
    Runnable updatePlayer;
    int selected = -1;
    ListView list;
    PopupShareActionProvider shareProvider;
    int scrollState;

    Map<File, Integer> durations = new TreeMap<>();

    public Recordings(Context context, ListView list) {
        super(context, 0);
        this.list = list;
        this.handler = new Handler();
        this.storage = new Storage(context);
        this.list.setOnScrollListener(this);
    }


    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        this.scrollState = scrollState;
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
    }

    public void scan(File dir) {
        setNotifyOnChange(false);
        clear();
        durations.clear();

        List<File> ff = storage.scan(dir);

        for (File f : ff) {
            if (f.isFile()) {
                MediaPlayer mp = null;
                try {
                    mp = MediaPlayer.create(getContext(), Uri.fromFile(f));
                } catch (IllegalStateException e) {
                    Log.d(TAG, f.toString(), e);
                }
                if (mp != null) {
                    int d = mp.getDuration();
                    mp.release();
                    durations.put(f, d);
                    add(f);
                } else {
                    Log.e(TAG, f.toString());
                }
            }
        }

        sort();
        notifyDataSetChanged();
    }

    public void sort() {
        sort(new SortFiles());
    }

    public void close() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (updatePlayer != null) {
            handler.removeCallbacks(updatePlayer);
            updatePlayer = null;
        }
    }

    public void load() {
        scan(storage.getStoragePath());
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(getContext());

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.recording, parent, false);
            convertView.setTag(-1);
        }

        final View view = convertView;
        final View base = convertView.findViewById(R.id.recording_base);

        if ((int) convertView.getTag() == TYPE_DELETED) {
            RemoveItemAnimation.restore(base);
            convertView.setTag(-1);
        }

        final File f = getItem(position);

        TextView title = (TextView) convertView.findViewById(R.id.recording_title);
        title.setText(f.getName());

        SimpleDateFormat s = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        TextView time = (TextView) convertView.findViewById(R.id.recording_time);
        time.setText(s.format(new Date(f.lastModified())));

        TextView dur = (TextView) convertView.findViewById(R.id.recording_duration);
        dur.setText(MainLibrary.formatDuration(getContext(), durations.get(f)));

        TextView size = (TextView) convertView.findViewById(R.id.recording_size);
        size.setText(MainLibrary.formatSize(getContext(), f.length()));

        final View playerBase = convertView.findViewById(R.id.recording_player);
        playerBase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });

        final Runnable delete = new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(R.string.delete_recording);
                builder.setMessage("...\\" + f.getName() + "\n\n" + getContext().getString(R.string.are_you_sure));
                builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        playerStop();
                        dialog.cancel();
                        RemoveItemAnimation.apply(list, base, new Runnable() {
                            @Override
                            public void run() {
                                f.delete();
                                view.setTag(TYPE_DELETED);
                                select(-1);
                                load();
                            }
                        });
                    }
                });
                builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
                builder.show();
            }
        };

        final Runnable rename = new Runnable() {
            @Override
            public void run() {
                final OpenFileDialog.EditTextDialog e = new OpenFileDialog.EditTextDialog(getContext());
                e.setTitle(getContext().getString(R.string.rename_recording));
                e.setText(Storage.getNameNoExt(f));
                e.setPositiveButton(new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String ext = Storage.getExt(f);
                        String s = String.format("%s.%s", e.getText(), ext);
                        File ff = new File(f.getParent(), s);
                        f.renameTo(ff);
                        load();
                    }
                });
                e.show();

            }
        };

        if (selected == position) {
            RecordingAnimation.apply(list, convertView, true, scrollState == SCROLL_STATE_IDLE && (int) convertView.getTag() == TYPE_COLLAPSED);
            convertView.setTag(TYPE_EXPANDED);

            updatePlayerText(convertView, f);

            final View play = convertView.findViewById(R.id.recording_player_play);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (player == null) {
                        playerPlay(playerBase, f);
                    } else if (player.isPlaying()) {
                        playerPause(playerBase, f);
                    } else {
                        playerPlay(playerBase, f);
                    }
                }
            });

            final View edit = convertView.findViewById(R.id.recording_player_edit);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rename.run();
                }
            });

            final View share = convertView.findViewById(R.id.recording_player_share);
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareProvider = new PopupShareActionProvider(getContext(), share);

                    Intent emailIntent = new Intent(Intent.ACTION_SEND);
                    emailIntent.setType(Factory.MP4A);
                    emailIntent.putExtra(Intent.EXTRA_EMAIL, "");
                    emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                    emailIntent.putExtra(Intent.EXTRA_SUBJECT, f.getName());
                    emailIntent.putExtra(Intent.EXTRA_TEXT, getContext().getString(R.string.shared_via, getContext().getString(R.string.app_name)));

                    shareProvider.setShareIntent(emailIntent);

                    shareProvider.show();
                }
            });

            View trash = convertView.findViewById(R.id.recording_player_trash);
            trash.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    delete.run();
                }
            });

            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(-1);
                }
            });
        } else {
            RecordingAnimation.apply(list, convertView, false, scrollState == SCROLL_STATE_IDLE && (int) convertView.getTag() == TYPE_EXPANDED);
            convertView.setTag(TYPE_COLLAPSED);

            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(position);
                }
            });
        }

        convertView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                PopupMenu popup = new PopupMenu(getContext(), v);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.menu_context, popup.getMenu());
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == R.id.action_delete) {
                            delete.run();
                            return true;
                        }
                        if (item.getItemId() == R.id.action_rename) {
                            rename.run();
                            return true;
                        }
                        return false;
                    }
                });
                popup.show();
                return true;
            }
        });

        return convertView;
    }

    void playerPlay(View v, File f) {
        if (player == null)
            player = MediaPlayer.create(getContext(), Uri.fromFile(f));
        if (player == null) {
            Toast.makeText(getContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        player.start();

        updatePlayerRun(v, f);
    }

    void playerPause(View v, File f) {
        if (player != null) {
            player.pause();
        }
        if (updatePlayer != null) {
            handler.removeCallbacks(updatePlayer);
            updatePlayer = null;
        }
        updatePlayerText(v, f);
    }

    void playerStop() {
        if (updatePlayer != null) {
            handler.removeCallbacks(updatePlayer);
            updatePlayer = null;
        }
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    void updatePlayerRun(final View v, final File f) {
        boolean playing = updatePlayerText(v, f);

        if (updatePlayer != null) {
            handler.removeCallbacks(updatePlayer);
            updatePlayer = null;
        }

        if (!playing) {
            playerStop(); // clear player instance
            updatePlayerText(v, f); // update length
            return;
        }

        updatePlayer = new Runnable() {
            @Override
            public void run() {
                updatePlayerRun(v, f);
            }
        };
        handler.postDelayed(updatePlayer, 200);
    }

    boolean updatePlayerText(final View v, final File f) {
        ImageView i = (ImageView) v.findViewById(R.id.recording_player_play);

        final boolean playing = player != null && player.isPlaying();

        i.setImageResource(playing ? R.drawable.ic_pause_black_24dp : R.drawable.ic_play_arrow_black_24dp);

        TextView start = (TextView) v.findViewById(R.id.recording_player_start);
        SeekBar bar = (SeekBar) v.findViewById(R.id.recording_player_seek);
        TextView end = (TextView) v.findViewById(R.id.recording_player_end);

        int c = 0;
        int d = durations.get(f);

        if (player != null) {
            c = player.getCurrentPosition();
            d = player.getDuration();
        }

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser)
                    return;

                if (player == null)
                    playerPlay(v, f);

                if (player != null) {
                    player.seekTo(progress);
                    if (!player.isPlaying())
                        playerPlay(v, f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        start.setText(MainLibrary.formatDuration(getContext(), c));
        bar.setMax(d);
        bar.setKeyProgressIncrement(1);
        bar.setProgress(c);
        end.setText("-" + MainLibrary.formatDuration(getContext(), d - c));

        return playing;
    }

    public void select(int pos) {
        selected = pos;
        notifyDataSetChanged();
        playerStop();
    }

    public int getSelected() {
        return selected;
    }
}