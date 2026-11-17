package com.rideledger;

import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

/** Local form state for quick entry, owned by the activity on the main thread. */
final class QuickEntryDrafts {
    interface Listener { void finished(String tripId, Exception error); }

    private final Bundle forms, photos;
    private final Set<String> submitting = new HashSet<>();
    private Listener listener;

    QuickEntryDrafts(Bundle state) {
        Bundle savedForms = state == null ? null : state.getBundle("quickForms");
        Bundle savedPhotos = state == null ? null : state.getBundle("quickPhotos");
        forms = savedForms == null ? new Bundle() : new Bundle(savedForms);
        photos = savedPhotos == null ? new Bundle() : new Bundle(savedPhotos);
        if (state == null) return;
        Bundle previous = state.getBundle("quickDraft");
        if (savedForms == null && previous != null && previous.getString("trip") != null)
            forms.putBundle(previous.getString("trip"), previous);
        String photoTrip = state.getString("receiptDraftTrip"), token = state.getString("receiptDraft");
        if (savedPhotos == null && photoTrip != null && token != null) photos.putString(photoTrip, token);
    }

    Bundle form(String tripId) { return forms.getBundle(tripId); }
    String photo(String tripId) { return photos.getString(tripId); }
    void putPhoto(String tripId, String token) { photos.putString(tripId, token); }
    void removePhoto(String tripId) { photos.remove(tripId); }
    void clearForm(String tripId) { forms.remove(tripId); }
    boolean isSubmitting(String tripId) { return submitting.contains(tripId); }

    void remember(String tripId, Bundle form) {
        if (isSubmitting(tripId)) return;
        if (form == null) clearForm(tripId);
        else forms.putBundle(tripId, form);
    }

    void beginSave(String tripId, Bundle form) {
        remember(tripId, form);
        submitting.add(tripId);
    }

    void finishSave(String tripId, Exception failure) {
        if (failure == null) clearForm(tripId);
        submitting.remove(tripId);
        if (listener != null) listener.finished(tripId, failure);
    }

    void listen(Listener listener) { this.listener = listener; }
    void unlisten(Listener listener) { if (this.listener == listener) this.listener = null; }

    void saveState(Bundle state) {
        Bundle savedForms = new Bundle(forms), savedPhotos = new Bundle(photos);
        // A submitted form is tracked by the live save callback, never restored as a new entry.
        for (String tripId : submitting) {
            savedForms.remove(tripId);
            savedPhotos.remove(tripId);
        }
        state.putBundle("quickForms", savedForms);
        state.putBundle("quickPhotos", savedPhotos);
    }
}
