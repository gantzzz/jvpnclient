package online.justvpn;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.Image;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public class ServerListItemAdaptor extends ArrayAdapter<ServerListItemDataModel> {
    private final Context context;
    private ArrayList<ServerListItemDataModel> dataSet;

    private static class ViewHolder
    {
        ImageView countryFlag;
        TextView ip;
        TextView countryName;
        Switch enableSwitch;
    }

    public ServerListItemAdaptor(@NonNull Context context, ArrayList<ServerListItemDataModel> Data)
    {
        super(context, R.layout.server_list_item);
        this.dataSet = Data;
        this.context = context;
    }

    @Override
    public int getCount()
    {
        // TODO Auto-generated method stub
        return dataSet.size();
    }

    @Override
    public ServerListItemDataModel getItem(int position) {
        // TODO Auto-generated method stub
        return dataSet.get(position);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        ServerListItemDataModel model = getItem(position);

        ViewHolder viewHolder;

        if (convertView == null)
        {
            viewHolder = new ViewHolder();
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.server_list_item, parent, false);
            viewHolder.countryFlag = convertView.findViewById(R.id.imageViewCountryFlag);
            viewHolder.ip = convertView.findViewById(R.id.textViewIP);
            viewHolder.countryName = convertView.findViewById(R.id.textViewCountry);
            viewHolder.enableSwitch = convertView.findViewById(R.id.enableSwitch);
            convertView.setTag(viewHolder);
        }
        else
        {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        switch (model.get_country())
        {
            case "NL":
                viewHolder.countryFlag.setImageResource(R.mipmap.ic_nl);
                break;
            case "RU":
                viewHolder.countryFlag.setImageResource(R.mipmap.ic_ru);
                break;
            case "US":
                viewHolder.countryFlag.setImageResource(R.mipmap.ic_us);
                break;
            default:
                break;
        }
        viewHolder.ip.setText(model.get_ip());
        viewHolder.countryName.setText(model.get_country());

        return convertView;
    }
}
