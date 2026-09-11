function goToMessage(anchor) {
    var el = document.getElementById(anchor);
    if (!el) {
        return true;
    }
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    var old = el.style.backgroundColor;
    el.style.backgroundColor = "#fff8cc";
    setTimeout(function () {
        el.style.backgroundColor = old;
    }, 1200);
    return false;
}
