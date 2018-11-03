package stepcounter.app;

import java.util.Date;
import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

public class Data extends RealmObject {
    //ID, 日時, 緯度, 経度, 移動速度
    @PrimaryKey
    private int id;
    private Date date;
    private float lat;
    private float lon;
    private float speed;
}
