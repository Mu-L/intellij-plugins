package jetbrains.plugins.yeoman.generators;


import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class YeomanGeneratorFullInfo implements YeomanGeneratorInfo {

  public static YeomanGeneratorFullInfo getFullInfo(YeomanGeneratorInfo info) {

    if (info instanceof YeomanGeneratorFullInfo) {
      return (YeomanGeneratorFullInfo)info;
    }
    return info instanceof YeomanInstalledGeneratorInfo ?
           ((YeomanInstalledGeneratorInfo)info).getFullInfo() :
           null;
  }

  /**
   * all fields from json
   */
  private @Nullable String name;

  private @Nullable String description;

  private @Nullable Object owner;

  private @Nullable String ownerWebsite;

  private @Nullable String website;

  private @Nullable String site;

  private @Nullable Object forks;
  private @Nullable Object created;
  private @Nullable Object updated;
  public int getStars() {
    return stars;
  }

  private int stars;

  private YeomanGeneratorFullInfo() {
  }


  public @Nullable String getWebsite() {
    return website == null ? site : website;
  }

  public @NlsSafe @NotNull String getDescription() {
    return StringUtil.notNullize(description);
  }

  @Override
  public @Nullable String getName() {
    return YeomanGeneratorInfo.Util.getName(name);
  }

  @Override
  public @Nullable String getYoName() {
    return YeomanGeneratorInfo.Util.getYoName(name);
  }

  @Override
  public int hashCode() {
    return name != null ? name.hashCode() : 0;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof YeomanGeneratorFullInfo && StringUtil.equals(name, ((YeomanGeneratorFullInfo)obj).name);
  }

  @Override
  public YeomanGeneratorFullInfo clone() {
    final YeomanGeneratorFullInfo info = new YeomanGeneratorFullInfo();
    info.name = name;
    info.description = description;
    info.owner = owner;
    info.ownerWebsite = ownerWebsite;
    info.forks = forks;
    info.created = created;
    info.updated = updated;
    info.stars = stars;
    info.website = website;
    info.site = site;
    return info;
  }

  @Override
  public int compareTo(YeomanGeneratorInfo o) {
    if (o instanceof YeomanGeneratorFullInfo fullInfo) {
      return (stars < fullInfo.stars) ? -1 : ((stars == fullInfo.stars) ? StringUtil.compare(this.getName(), o.getName(), false) : 1);
    }
    return StringUtil.compare(this.getName(), o.getName(), false);
  }
}
