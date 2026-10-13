package com.nexora.hotel.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nexora.hotel.domain.ReservationStateMachine;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.dto.catalog.SettingsDtos.PublicHotel;
import com.nexora.hotel.i18n.Translator;
import com.nexora.hotel.repository.ReservationLedger;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

/** Comprobante de reservación en PDF (OpenPDF, como en DemoPV), armado en memoria. */
@Service
public class ReceiptPdfService {

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final Color INK = new Color(10, 15, 28);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color LINE = new Color(226, 232, 240);

    public byte[] render(ReservationDetail d, PublicHotel hotel, String currency) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER, 48, 48, 48, 48);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
        Font h = new Font(Font.HELVETICA, 11, Font.BOLD, INK);
        Font normal = new Font(Font.HELVETICA, 10, Font.NORMAL, INK);
        Font small = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);

        doc.add(new Paragraph(hotel.name(), title));
        if (hotel.address() != null) {
            doc.add(new Paragraph(hotel.address(), small));
        }
        if (hotel.phone() != null || hotel.contactEmail() != null) {
            doc.add(new Paragraph(String.join(" · ", java.util.stream.Stream.of(hotel.phone(), hotel.contactEmail())
                    .filter(java.util.Objects::nonNull).toList()), small));
        }
        doc.add(Chunk.NEWLINE);

        var s = d.summary();
        Locale loc = Translator.locale();
        boolean en = Translator.english();
        PdfPTable head = new PdfPTable(new float[]{1, 1});
        head.setWidthPercentage(100);
        head.addCell(cell(new Phrase(tr("Comprobante de reservación\n"), h), false));
        head.addCell(cell(new Phrase(tr("Folio {0}\n{1}", s.folio(), tr(ReservationStateMachine.label(s.status())).toUpperCase(loc)), h), true));
        doc.add(head);
        doc.add(Chunk.NEWLINE);

        DateTimeFormatter f = DateTimeFormatter.ofPattern(en ? "EEE, MMM d, yyyy" : "EEE d MMM yyyy", loc);
        PdfPTable info = new PdfPTable(new float[]{1, 2});
        info.setWidthPercentage(100);
        row(info, tr("Huésped"), s.guestName(), normal, small);
        row(info, tr("Llegada"), tr("{0} (desde {1})", s.checkIn().format(f), d.policy().get("checkInTime")), normal, small);
        row(info, tr("Salida"), tr("{0} (hasta {1})", s.checkOut().format(f), d.policy().get("checkOutTime")), normal, small);
        row(info, tr("Noches"), String.valueOf(s.nights()), normal, small);
        row(info, tr("Huéspedes"), s.children() > 0 ? tr("{0} adulto(s), {1} menor(es)", s.adults(), s.children()) : tr("{0} adulto(s)", s.adults()), normal, small);
        row(info, tr("Habitación"), s.roomTypeName() + (s.roomNumber() == null ? "" : " · " + s.roomNumber()), normal, small);
        doc.add(info);
        doc.add(Chunk.NEWLINE);

        doc.add(new Paragraph(tr("Desglose"), h));
        PdfPTable lines = new PdfPTable(new float[]{3, 1});
        lines.setWidthPercentage(100);
        for (ReservationLedger.PriceLine l : d.priceLines()) {
            String label = (l.date() == null ? "" : l.date().format(DateTimeFormatter.ofPattern(en ? "MM/dd" : "dd/MM", loc)) + "  ")
                    + tr(l.description()) + (l.quantity().compareTo(BigDecimal.ONE) > 0 ? " × " + l.quantity().stripTrailingZeros().toPlainString() : "");
            lines.addCell(cell(new Phrase(label, l.type().equals("TAX_INCLUDED") ? small : normal), false));
            lines.addCell(cell(new Phrase(money(l.amount(), currency, loc), l.type().equals("TAX_INCLUDED") ? small : normal), true));
        }
        PdfPCell totalLabel = cell(new Phrase(tr("Total"), h), false);
        PdfPCell totalValue = cell(new Phrase(money(s.total(), currency, loc), h), true);
        totalLabel.setBorderWidthTop(1);
        totalValue.setBorderWidthTop(1);
        lines.addCell(totalLabel);
        lines.addCell(totalValue);
        doc.add(lines);

        if (d.penaltyAmount() != null && d.penaltyAmount().signum() > 0) {
            doc.add(new Paragraph(tr("Penalización aplicada: {0}", money(d.penaltyAmount(), currency, loc)), normal));
        }
        doc.add(Chunk.NEWLINE);

        var account = d.account();
        if (account != null) {
            DateTimeFormatter when = DateTimeFormatter.ofPattern(en ? "MM/dd/yyyy h:mm a" : "dd/MM/yyyy HH:mm", loc).withZone(java.time.ZoneId.of(hotel.timezone()));
            var activeCharges = account.charges().stream().filter(c -> !c.voided()).toList();
            if (!activeCharges.isEmpty()) {
                doc.add(new Paragraph(tr("Cargos durante la estancia"), h));
                PdfPTable ct = new PdfPTable(new float[]{3, 1});
                ct.setWidthPercentage(100);
                for (var c : activeCharges) {
                    ct.addCell(cell(new Phrase(c.description() + (c.quantity().compareTo(BigDecimal.ONE) > 0
                            ? " × " + c.quantity().stripTrailingZeros().toPlainString() : ""), normal), false));
                    ct.addCell(cell(new Phrase(money(c.amount(), currency, loc), normal), true));
                }
                doc.add(ct);
                doc.add(Chunk.NEWLINE);
            }
            var movements = account.payments().stream()
                    .filter(p -> p.status() == com.nexora.hotel.enums.PaymentEnums.PaymentStatus.COMPLETED).toList();
            doc.add(new Paragraph(tr("Pagos"), h));
            PdfPTable pt = new PdfPTable(new float[]{3, 1});
            pt.setWidthPercentage(100);
            if (movements.isEmpty()) {
                pt.addCell(cell(new Phrase(tr("Sin pagos registrados"), small), false));
                pt.addCell(cell(new Phrase("", small), true));
            }
            for (var p : movements) {
                String kind = switch (p.kind()) {
                    case PAYMENT -> tr("Pago");
                    case REFUND -> tr("Reembolso");
                    case REVERSAL -> tr("Reverso");
                };
                String label = when.format(p.completedAt()) + "  " + kind + " · " + tr(PaymentService.methodLabel(p.method()))
                        + (p.cardLast4() == null ? "" : " ···· " + p.cardLast4());
                pt.addCell(cell(new Phrase(label, normal), false));
                pt.addCell(cell(new Phrase((p.kind().isOutflow() ? "- " : "") + money(p.amount(), currency, loc), normal), true));
            }
            PdfPCell dueLabel = cell(new Phrase(tr("Importe de la cuenta"), normal), false);
            PdfPCell dueValue = cell(new Phrase(money(account.amountDue(), currency, loc), normal), true);
            pt.addCell(dueLabel);
            pt.addCell(dueValue);
            pt.addCell(cell(new Phrase(tr("Pagado"), normal), false));
            pt.addCell(cell(new Phrase(money(account.paid(), currency, loc), normal), true));
            boolean credit = account.balance().signum() < 0;
            PdfPCell balanceLabel = cell(new Phrase(tr(credit ? "Saldo a favor del huésped" : "Saldo pendiente"), h), false);
            PdfPCell balanceValue = cell(new Phrase(money(account.balance().abs(), currency, loc), h), true);
            balanceLabel.setBorderWidthTop(1);
            balanceValue.setBorderWidthTop(1);
            pt.addCell(balanceLabel);
            pt.addCell(balanceValue);
            doc.add(pt);
            doc.add(Chunk.NEWLINE);
        }
        Object hours = d.policy().get("freeCancelHours");
        doc.add(new Paragraph(tr("Política aceptada (versión {0}): cancelación sin costo hasta {1} horas antes de la llegada.",
                d.policy().get("version"), hours), small));
        doc.add(new Paragraph(tr("Documento informativo; no es un comprobante fiscal (CFDI)."), small));
        doc.close();
        return out.toByteArray();
    }

    private static void row(PdfPTable t, String label, String value, Font normal, Font small) {
        t.addCell(cell(new Phrase(label, small), false));
        t.addCell(cell(new Phrase(value, normal), false));
    }

    private static PdfPCell cell(Phrase p, boolean right) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(PdfPCell.BOTTOM);
        c.setBorderColor(LINE);
        c.setPadding(6);
        c.setHorizontalAlignment(right ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return c;
    }

    private static String tr(String text, Object... args) {
        return Translator.tr(text, args);
    }

    private static String money(BigDecimal v, String currency, Locale locale) {
        NumberFormat f = NumberFormat.getCurrencyInstance(locale);
        f.setCurrency(Currency.getInstance(currency));
        return f.format(v);
    }
}
