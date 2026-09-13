package dev.eye.internalrec;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.VH> {

    public interface Action {
        void onShare(Recording r);
        void onDelete(Recording r);
    }

    private final List<Recording> items;
    private final Action action;

    public RecordingsAdapter(List<Recording> items, Action action) {
        this.items = items;
        this.action = action;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        h.name.setText(r.name());
        h.meta.setText(r.meta());
        h.share.setOnClickListener(v -> action.onShare(r));
        h.delete.setOnClickListener(v -> action.onDelete(r));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, meta;
        final View share, delete;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            meta = v.findViewById(R.id.tvMeta);
            share = v.findViewById(R.id.btnShare);
            delete = v.findViewById(R.id.btnDelete);
        }
    }
}
