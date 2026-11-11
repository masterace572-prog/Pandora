import base64
import concurrent.futures
import pathlib
import urllib.request


Versions = range(10, 18)
Sources = {
    "ActivityThread": "android/app/ActivityThread.java",
    "AppComponentFactory": "android/app/AppComponentFactory.java",
}
Output = pathlib.Path(__file__).resolve().parents[1] / "pandora-core" / "build" / "aosp"


def Download(Source):
    Version, Name, RelativePath = Source
    Destination = Output / f"android{Version}" / f"{Name}.java"
    if Destination.is_file():
        return Version, Name, Destination.read_text(encoding="utf-8")
    Url = (
        "https://android.googlesource.com/platform/frameworks/base/+"
        f"/refs/heads/android{Version}-release/core/java/{RelativePath}?format=TEXT"
    )
    WithTimeout = 30
    with urllib.request.urlopen(Url, timeout=WithTimeout) as Response:
        Content = base64.b64decode(Response.read())
    Destination.parent.mkdir(parents=True, exist_ok=True)
    Destination.write_bytes(Content)
    return Version, Name, Content.decode("utf-8")


def Main():
    Requests = [
        (Version, Name, RelativePath)
        for Version in Versions
        for Name, RelativePath in Sources.items()
    ]
    Results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as Executor:
        for Version, Name, Content in Executor.map(Download, Requests):
            Results[(Version, Name)] = Content

    Checks = {
        "ActivityThread.currentActivityThread": ("ActivityThread", "currentActivityThread("),
        "ActivityThread.mBoundApplication": ("ActivityThread", "mBoundApplication"),
        "AppComponentFactory.instantiateClassLoader": (
            "AppComponentFactory", "instantiateClassLoader("),
        "AppComponentFactory.instantiateApplication": (
            "AppComponentFactory", "instantiateApplication("),
        "AppComponentFactory.instantiateActivity": (
            "AppComponentFactory", "instantiateActivity("),
        "AppComponentFactory.instantiateService": (
            "AppComponentFactory", "instantiateService("),
        "AppComponentFactory.instantiateReceiver": (
            "AppComponentFactory", "instantiateReceiver("),
        "AppComponentFactory.instantiateProvider": (
            "AppComponentFactory", "instantiateProvider("),
    }
    Header = "| AOSP source symbol | " + " | ".join(
        f"[Android {Version}](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android{Version}-release/core/java/)"
        for Version in Versions
    ) + " |"
    Separator = "| --- |" + " --- |" * len(Versions)
    Lines = ["# AOSP source compatibility", "", Header, Separator]
    for Label, (Name, Symbol) in Checks.items():
        Values = ["yes" if Symbol in Results[(Version, Name)] else "no" for Version in Versions]
        Lines.append("| " + Label + " | " + " | ".join(Values) + " |")
    Lines.extend([
        "",
        "This report checks source symbol presence. It does not establish runtime access",
        "to hidden APIs or device-specific behavior.",
        "",
        "Sources were downloaded from the `android10-release` through",
        "`android17-release` branches of AOSP frameworks/base on android.googlesource.com.",
        "",
    ])
    Report = pathlib.Path(__file__).resolve().parents[1] / "AOSP-COMPATIBILITY.md"
    Report.write_text("\n".join(Lines), encoding="utf-8")
    print(Report)


if __name__ == "__main__":
    Main()
