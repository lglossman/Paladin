package mobi.tattu.utils;

import android.content.Context;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mobi.tattu.utils.activities.BaseActivity;
import mobi.tattu.utils.views.HintAdapter;
import rx.android.schedulers.AndroidSchedulers;

/**
 * Created by Leandro on 7/8/2015.
 */
public class DialogUtils {

    /**
     * Muestra un dialog con radio boxes con estilo Material Design.
     *
     * @param ctx      Activity
     * @param items    Array de los items. Los items deben implementar el metodo toString
     * @param selected El item seleccionado por defecto o null si no hay valor preseleccionado
     * @param title    El titulo del dialogo
     * @param listener Listener para la seleccion o null. El listener debe devolver true si es
     *                 necesario cerrar el dialogo luego de la selección. El lister recibe
     *                 el elemento seleccionado y el objeto AlertDialog
     * @param <T>      El tipo de item. El metodo toString debe devolver el nombre del item que va a
     *                 aparecer en la UI.
     */
    public static <T> AlertDialog singleChoice(Context ctx,
                                               T[] items,
                                               T selected,
                                               String title,
                                               F.Function2<T, AlertDialog, Boolean> listener) {

        ArrayAdapter<T> adapter = new ArrayAdapter(ctx,
                R.layout.select_dialog_singlechoice_material,
                items);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setAdapter(adapter, (d, which) -> {
                })
                .create();

        dialog.getListView().setItemsCanFocus(false);
        dialog.getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            if (listener == null || listener.apply(adapter.getItem(position), dialog)) {
                dialog.dismiss();
            }
        });
        dialog.show();

        if (selected != null) {
            dialog.getListView().setItemChecked(adapter.getPosition(selected), true);
        }

        return dialog;
    }

    public static <T> AlertDialog singleChoiceConfirmation(Context ctx,
                                                           T[] items,
                                                           T selected,
                                                           String title,
                                                           F.Function2<T, AlertDialog, Boolean> listener) {

        ArrayAdapter<T> adapter = new ArrayAdapter(ctx,
                R.layout.select_dialog_singlechoice_material,
                items);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setAdapter(adapter, (d, which) -> {
                })
                .setPositiveButton(R.string.ok, (d, which) -> {
                    AlertDialog ad = ((AlertDialog) d);
                    int checkedItemPosition = ad.getListView().getCheckedItemPosition();
                    T item = null;
                    if (checkedItemPosition != ListView.INVALID_POSITION) {
                        item = adapter.getItem(checkedItemPosition);
                    }
                    if (listener == null || listener.apply(item, ad)) {
                        d.dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, (d, which) -> {
                    d.dismiss();
                })
                .create();

        dialog.getListView().setItemsCanFocus(false);
        dialog.getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
        });
        dialog.show();

        if (selected != null) {
            dialog.getListView().setItemChecked(adapter.getPosition(selected), true);
        }

        return dialog;
    }

    public static <T> AlertDialog multiChoice(Context ctx,
                                              List<T> items,
                                              List<T> preselected,
                                              List<T> disabled,
                                              String title,
                                              F.Function2<List<T>, AlertDialog, Boolean> listener) {

        List<T> selectedItems = new ArrayList<>(items.size());
        selectedItems.addAll(preselected);

        final ArrayAdapter<T> arrayAdapter = new DialogListAdapter<>(ctx, R.layout.select_dialog_multichoice_material, items, disabled);
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setAdapter(arrayAdapter, (d, which) -> {
                })
                .setPositiveButton(R.string.ok, (d, which) -> {
                })
                .create();

        dialog.getListView().setItemsCanFocus(false);
        dialog.getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            T clicked = items.get(position);
            if (selectedItems.contains(clicked)) {
                selectedItems.remove(clicked);
            } else {
                selectedItems.add(clicked);
            }
        });
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (listener == null || listener.apply(selectedItems, dialog)) {
                dialog.dismiss();
            }
        });

        if (preselected != null && !preselected.isEmpty()) {
            for (T item : items) {
                dialog.getListView().setItemChecked(items.indexOf(item), preselected.contains(item));
            }
        }

        return dialog;
    }

    /**
     * Adapter que permite deshabilitar uno o mas items
     */
    private static class DialogListAdapter<T> extends ArrayAdapter<T> {

        private List<T> disabled;

        /**
         * @param objects  Items a mostrar(deben implementar toString)
         * @param disabled Items de la lista anterior que van a ser deshabilitados
         */
        public DialogListAdapter(Context context, int resource, List<T> objects, List<T> disabled) {
            super(context, resource, objects);
            this.disabled = disabled;
        }

        @Override
        public boolean isEnabled(int position) {
            if (disabled != null && !disabled.isEmpty()) {
                return !disabled.contains(getItem(position));
            }
            return true;
        }
    }

    public static void alert(Context ctx, String title, String okButton, F.Callback<Boolean> listener) {
        alert(ctx, title, null, okButton, null, listener);
    }

    public static void alert(Context ctx, String title, String text, String okButton, F.Callback<Boolean> listener) {
        alert(ctx, title, text, okButton, null, listener);
    }

    public static void alert(Context ctx, String title, String text, String okBUttom, String cancelButton, F.Callback<Boolean> listener) {
        AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
        alert.setTitle(title);

        if (StringUtils.isNotBlank(text)) {
            alert.setMessage(text);
        }

        alert.setPositiveButton(okBUttom, (dialog, whichButton) -> {
            listener.invoke(true);
            dialog.dismiss();
        });

        if (StringUtils.isNotBlank(cancelButton)) {
            alert.setNegativeButton(cancelButton, (dialog, whichButton) -> {
                listener.invoke(false);
                dialog.dismiss();
            });
        }

        alert.show();
    }

    public static void ask(Context ctx, String title, F.Function<String, Boolean> listener) {
        ask(ctx, title, ctx.getString(R.string.ok), ctx.getString(R.string.cancel), listener);
    }

    public static void ask(Context ctx, String title, String okBUttom, String cancelButton, F.Function<String, Boolean> listener) {
        AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
        alert.setTitle(title);

        final EditText edittext = new EditText(ctx);
        alert.setView(edittext);

        alert.setPositiveButton(okBUttom, (dialog, whichButton) -> {
            String text = edittext.getText().toString();
            if (listener.apply(text)) {
                dialog.dismiss();
            }
        });

        alert.setNegativeButton(cancelButton, (dialog, whichButton) -> {
            // what ever you want to do with No option.
        });

        alert.show();
    }

    public static AlertDialog custom(BaseActivity ctx,
                                     String title,
                                     View content,
                                     String positive,
                                     String negative,
                                     F.Function2<DialogInterface, Boolean, Boolean> callback) {

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(title);
        builder.setView(content);
        if (StringUtils.isNotBlank(positive)) {
            builder.setPositiveButton(positive, (dialog, whichButton) -> {
            });
        }
        if (StringUtils.isNotBlank(negative)) {
            builder.setNegativeButton(negative, (dialog, whichButton) -> {
            });
        }

        AlertDialog dialog = builder.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
           if (callback.apply(dialog, true)) {
               dialog.dismiss();
           }
        });
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (callback.apply(dialog, false)) {
                dialog.dismiss();
            }
        });

        return dialog;
    }

}
