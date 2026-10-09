/** 원본과 생성 이미지의 고정 식별자로 표시/다운로드 순서와 임시 휴지통을 관리한다. */
export class ImageOrder {
    constructor(count = 0) {
        this.order = Array.from({ length: count }, (_, index) => index);
        this.trash = [];
        this.selected = null;
    }

    select(id) {
        this.selected = this.order.includes(id) ? id : null;
    }

    append(id) {
        if (this.order.includes(id) || this.trash.some(item => item.id === id)) return false;
        this.order.push(id);
        return true;
    }

    move(id, position) {
        const from = this.order.indexOf(id);
        if (from < 0) return false;
        this.order.splice(from, 1);
        this.order.splice(Math.max(0, Math.min(position, this.order.length)), 0, id);
        return true;
    }

    insert(id, targetId = null, after = false) {
        if (id === targetId) return false;
        if (!this.order.includes(id) && !this.trash.some(item => item.id === id)) return false;
        if (targetId !== null && !this.order.includes(targetId)) return false;
        this.order = this.order.filter(item => item !== id);
        this.trash = this.trash.filter(item => item.id !== id);
        const position = targetId === null ? this.order.length : this.order.indexOf(targetId) + Number(after);
        this.order.splice(position, 0, id);
        return true;
    }

    discard(id) {
        const position = this.order.indexOf(id);
        if (position < 0) return false;
        this.trash.push({ id, position, before: this.order[position - 1], after: this.order[position + 1] });
        this.order.splice(position, 1);
        if (this.selected === id) this.selected = null;
        return true;
    }

    restore(id) {
        const entry = this.trash.find(item => item.id === id);
        if (!entry) return false;
        const next = this.order.indexOf(entry.after);
        const previous = this.order.indexOf(entry.before);
        const position = next >= 0 ? next : previous >= 0 ? previous + 1 : Math.min(entry.position, this.order.length);
        this.trash = this.trash.filter(item => item.id !== id);
        this.order.splice(position, 0, id);
        return true;
    }

    restoreAll() {
        [...this.trash].reverse().forEach(item => this.restore(item.id));
    }
}
