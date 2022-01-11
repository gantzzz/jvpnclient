package online.justvpn;

public class ServerListItemDataModel
{
    private String mIp;
    private String mCountry;

    public ServerListItemDataModel(String ip, String country)
    {
        mIp = ip;
        mCountry = country;
    }

    public String get_ip()
    {
        return mIp;
    }
    public String get_country()
    {
        return mCountry;
    }
}
