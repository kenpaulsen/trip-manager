package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.Language;

/**
 * The Spanish and Croatian versions of the built-in EMAIL templates: the seven transactional MAIL starters and the
 * four reminder starting texts ({@code docs/content-templates.md}, "Language versions of an email").
 *
 * <p>Each one is installed as its own MAIL row under the English starter's id plus {@code @} and the language tag
 * ({@code registration-received@es}, the {@link PageVersions} convention), so an administrator edits it on the
 * Templates page like any other row, and an installed row is never rewritten by a later install.
 *
 * <p>Reviewed and approved by the owner with the bundles ({@code docs/i18n.md}): every row's description says
 * which language and which starter it is. The {@code {{tokens}}} are the English starters' own, character for
 * character: the senders fill the same names whatever the language.
 */
public final class StarterTranslations {

    /** One version: the English starter it translates, its language, its subject (the row's NAME) and body. */
    public record Version(String baseId, Language language, String subject, String body) {

        /** The id the version is installed under. */
        public String id() {
            return PageVersions.keyFor(baseId, language);
        }
    }

    public static final List<Version> VERSIONS = List.of(
            // ---------------------------------------------------------------- registration-received
            new Version(StarterTemplates.REGISTRATION_RECEIVED_ID, Language.Spanish,
                    "Inscripción recibida - {{tripTitle}}",
                    """
                    <p>¡Gracias por inscribirte en <b>{{tripTitle}}</b>!</p>
                    <p>Recibimos tu solicitud para:</p>
                    {{travelersBlock}}
                    <p>El siguiente paso es la aprobación: revisamos cada inscripción y te escribiremos en cuanto
                    la tuya esté confirmada.</p>
                    <p><a href="{{tripUrl}}">Ver los detalles del viaje</a></p>
                    <p>¿Tienes preguntas? Responde a este correo y te contestaremos.</p>
                    """),
            new Version(StarterTemplates.REGISTRATION_RECEIVED_ID, Language.Croatian,
                    "Prijava zaprimljena - {{tripTitle}}",
                    """
                    <p>Hvala vam na prijavi za <b>{{tripTitle}}</b>!</p>
                    <p>Zaprimili smo vašu prijavu za:</p>
                    {{travelersBlock}}
                    <p>Sljedeći korak je odobrenje: pregledavamo svaku prijavu i javit ćemo vam se čim vaša
                    bude potvrđena.</p>
                    <p><a href="{{tripUrl}}">Pogledaj pojedinosti putovanja</a></p>
                    <p>Imate pitanja? Samo odgovorite na ovaj e-mail i javit ćemo vam se.</p>
                    """),
            // ---------------------------------------------------------------- registration-approved
            new Version(StarterTemplates.REGISTRATION_APPROVED_ID, Language.Spanish,
                    "Inscripción confirmada - {{tripTitle}}",
                    """
                    <p>Te damos la bienvenida a <b>{{tripTitle}}</b>, {{firstName}}.</p>
                    <p>Tu inscripción fue aprobada. Nos alegra mucho que vengas.</p>
                    {{ticketBlock}}
                    <p><a href="{{itineraryUrl}}">Ver tu viaje</a></p>
                    <p>Asegúrate de que <a href="{{profileUrl}}">tu perfil</a> esté completo (pasaporte, fecha
                    de nacimiento, contacto de emergencia): lo necesitamos para organizar el viaje.</p>
                    <p>¿Tienes preguntas? Responde a este correo y te contestaremos.</p>
                    """),
            new Version(StarterTemplates.REGISTRATION_APPROVED_ID, Language.Croatian,
                    "Prijava potvrđena - {{tripTitle}}",
                    """
                    <p>{{firstName}}, dobro došli na <b>{{tripTitle}}</b>!</p>
                    <p>Vaša prijava je odobrena. Jako nam je drago što dolazite.</p>
                    {{ticketBlock}}
                    <p><a href="{{itineraryUrl}}">Pogledaj svoje putovanje</a></p>
                    <p>Provjerite je li <a href="{{profileUrl}}">vaš profil</a> potpun (putovnica, datum
                    rođenja, kontakt za hitne slučajeve): trebamo ga za organizaciju putovanja.</p>
                    <p>Imate pitanja? Samo odgovorite na ovaj e-mail i javit ćemo vam se.</p>
                    """),
            // ---------------------------------------------------------------- support-request
            new Version(StarterTemplates.SUPPORT_REQUEST_ID, Language.Spanish,
                    "{{subject}}",
                    """
                    <p>{{requestBlock}}</p>
                    <p style="font-size:0.85em;color:#666;">Recibes este correo porque administras el canal
                    de soporte (página de configuración). Quien hizo la solicitud no puede ver el canal;
                    contáctalo directamente.</p>
                    """),
            new Version(StarterTemplates.SUPPORT_REQUEST_ID, Language.Croatian,
                    "{{subject}}",
                    """
                    <p>{{requestBlock}}</p>
                    <p style="font-size:0.85em;color:#666;">Ovaj e-mail primate jer ste administrator kanala
                    podrške (stranica s postavkama). Podnositelj zahtjeva ne vidi kanal; javite mu se
                    izravno.</p>
                    """),
            // ---------------------------------------------------------------- payment-confirmation
            new Version(StarterTemplates.PAYMENT_CONFIRMATION_ID, Language.Spanish,
                    "Pago recibido - {{tripTitle}}",
                    """
                    <p>Hola, {{payerName}}:</p>
                    <p>¡Gracias! {{orgName}} recibió tu pago de <b>{{totalPaid}}</b> para
                    <b>{{tripTitle}}</b>.</p>
                    {{amountsBlock}}
                    <p>{{feeNote}}</p>
                    <p>{{donationNote}}</p>
                    <p style="font-size:0.85em;color:#666;">Procesador: {{processorName}} &middot;
                    id de la transacción {{captureId}} &middot; {{paymentDate}}</p>
                    """),
            new Version(StarterTemplates.PAYMENT_CONFIRMATION_ID, Language.Croatian,
                    "Uplata zaprimljena - {{tripTitle}}",
                    """
                    <p>Pozdrav, {{payerName}},</p>
                    <p>Hvala! Organizacija {{orgName}} zaprimila je vašu uplatu od <b>{{totalPaid}}</b> za
                    <b>{{tripTitle}}</b>.</p>
                    {{amountsBlock}}
                    <p>{{feeNote}}</p>
                    <p>{{donationNote}}</p>
                    <p style="font-size:0.85em;color:#666;">Procesor: {{processorName}} &middot;
                    ID transakcije {{captureId}} &middot; {{paymentDate}}</p>
                    """),
            // ---------------------------------------------------------------- payment-failed
            new Version(StarterTemplates.PAYMENT_FAILED_ID, Language.Spanish,
                    "Pago no completado - {{tripTitle}}",
                    """
                    <p>Hola, {{payerName}}:</p>
                    <p>Lamentablemente tu pago de <b>{{totalPaid}}</b> para <b>{{tripTitle}}</b> no se
                    completó: {{failureReason}}.</p>
                    <p>No se registró nada en tu saldo con {{orgName}} y no se te cobró. Vuelve a hacer el
                    pago, o responde a este correo si tienes preguntas.</p>
                    <p style="font-size:0.85em;color:#666;">Procesador: {{processorName}} &middot;
                    referencia {{captureId}}</p>
                    """),
            new Version(StarterTemplates.PAYMENT_FAILED_ID, Language.Croatian,
                    "Uplata nije izvršena - {{tripTitle}}",
                    """
                    <p>Pozdrav, {{payerName}},</p>
                    <p>Nažalost, vaša uplata od <b>{{totalPaid}}</b> za <b>{{tripTitle}}</b> nije prošla:
                    {{failureReason}}.</p>
                    <p>Na vaše stanje kod organizacije {{orgName}} ništa nije zabilježeno i ništa vam nije
                    naplaćeno. Ponovite uplatu ili odgovorite na ovaj e-mail ako imate pitanja.</p>
                    <p style="font-size:0.85em;color:#666;">Procesor: {{processorName}} &middot;
                    referenca {{captureId}}</p>
                    """),
            // ---------------------------------------------------------------- checkout-confirmation
            new Version(StarterTemplates.CHECKOUT_CONFIRMATION_ID, Language.Spanish,
                    "Inscripción confirmada - {{tripTitle}}",
                    """
                    <p>Hola, {{payerName}}:</p>
                    <p>Gracias por inscribirte en <b>{{tripTitle}}</b> ({{tripDates}}).</p>
                    {{registrantsBlock}}
                    <p>{{statusNote}}</p>
                    <p>Total pagado: <b>{{totalPaid}}</b> ({{paymentMethod}}). {{feeNote}}</p>
                    <p style="font-size:0.85em;color:#666;">{{orgName}} &middot; referencia {{captureId}}
                    &middot; {{paymentDate}}</p>
                    """),
            new Version(StarterTemplates.CHECKOUT_CONFIRMATION_ID, Language.Croatian,
                    "Prijava potvrđena - {{tripTitle}}",
                    """
                    <p>Pozdrav, {{payerName}},</p>
                    <p>Hvala vam na prijavi za <b>{{tripTitle}}</b> ({{tripDates}}).</p>
                    {{registrantsBlock}}
                    <p>{{statusNote}}</p>
                    <p>Ukupno uplaćeno: <b>{{totalPaid}}</b> ({{paymentMethod}}). {{feeNote}}</p>
                    <p style="font-size:0.85em;color:#666;">{{orgName}} &middot; referenca {{captureId}}
                    &middot; {{paymentDate}}</p>
                    """),
            // ---------------------------------------------------------------- org-invite
            new Version(StarterTemplates.ORG_INVITE_ID, Language.Spanish,
                    "{{orgName}} te invita a {{siteHost}}",
                    """
                    <p><b>{{orgName}}</b> te invitó a unirte en {{siteName}}.</p>
                    <p><a href="{{createAccountUrl}}">Inicia sesión o crea tu cuenta</a> para empezar.</p>
                    <p>Cuando tengas una cuenta, la administración de {{orgName}} podrá añadirte como
                    miembro, y verás los viajes y la información de tu organización al iniciar sesión.</p>
                    <p>¿Tienes preguntas? Responde a este correo.</p>
                    """),
            new Version(StarterTemplates.ORG_INVITE_ID, Language.Croatian,
                    "Poziv na {{siteHost}}: {{orgName}}",
                    """
                    <p>Organizacija <b>{{orgName}}</b> poziva vas da im se pridružite na {{siteName}}.</p>
                    <p><a href="{{createAccountUrl}}">Prijavite se ili otvorite račun</a> da započnete.</p>
                    <p>Kad budete imali račun, administrator organizacije {{orgName}} moći će vas dodati kao
                    člana, a pri prijavi ćete vidjeti putovanja i informacije svoje organizacije.</p>
                    <p>Imate pitanja? Samo odgovorite na ovaj e-mail.</p>
                    """),
            // ---------------------------------------------------------------- reminder-balance-due
            new Version(ReminderDefaults.BALANCE_DUE_ID, Language.Spanish,
                    "Tu saldo de {{tripTitle}}",
                    """
                    <p>Hola, {{firstName}}:</p>
                    <p>Te recordamos el saldo de <b>{{tripTitle}}</b>.</p>
                    <p>Saldo de {{travelerName}}: <b>{{balanceDue}}</b><br/>
                    Vence: {{balanceDueOn}}</p>
                    <p><a href="{{paymentLink}}">Hacer un pago</a></p>
                    <p>Si ya pagaste, gracias; responde a este correo para que revisemos nuestros registros.</p>
                    """),
            new Version(ReminderDefaults.BALANCE_DUE_ID, Language.Croatian,
                    "Vaše stanje za {{tripTitle}}",
                    """
                    <p>Pozdrav, {{firstName}},</p>
                    <p>Podsjećamo vas na stanje za <b>{{tripTitle}}</b>.</p>
                    <p>Stanje za putnika {{travelerName}}: <b>{{balanceDue}}</b><br/>
                    Rok: {{balanceDueOn}}</p>
                    <p><a href="{{paymentLink}}">Izvrši uplatu</a></p>
                    <p>Ako ste već platili, hvala vam; odgovorite na ovaj e-mail da provjerimo svoju evidenciju.</p>
                    """),
            // ---------------------------------------------------------------- reminder-waiver
            new Version(ReminderDefaults.WAIVER_ID, Language.Spanish,
                    "Firma tus documentos de {{tripTitle}}",
                    """
                    <p>Hola, {{firstName}}:</p>
                    <p><b>{{tripTitle}}</b> necesita la firma de {{travelerName}} en: {{documentList}}.</p>
                    <p><a href="{{documentsLink}}">Leer y firmar</a></p>
                    <p>¿Tienes preguntas? Responde a este correo.</p>
                    """),
            new Version(ReminderDefaults.WAIVER_ID, Language.Croatian,
                    "Potpišite dokumente za {{tripTitle}}",
                    """
                    <p>Pozdrav, {{firstName}},</p>
                    <p>Za <b>{{tripTitle}}</b> potreban je potpis putnika {{travelerName}} na: {{documentList}}.</p>
                    <p><a href="{{documentsLink}}">Pročitaj i potpiši</a></p>
                    <p>Imate pitanja? Samo odgovorite na ovaj e-mail.</p>
                    """),
            // ---------------------------------------------------------------- reminder-passport
            new Version(ReminderDefaults.PASSPORT_ID, Language.Spanish,
                    "Revisión de pasaportes para {{tripTitle}}",
                    """
                    <p>Hola, {{firstName}}:</p>
                    <p>Estamos revisando los pasaportes para <b>{{tripTitle}}</b> ({{tripDates}}).</p>
                    <p>Para {{travelerName}}: {{passportIssue}}</p>
                    <p>Muchos países exigen un pasaporte válido durante 6 meses después del regreso.
                    <a href="{{profileLink}}">Actualiza el pasaporte en tu perfil</a> cuando lo tengas.</p>
                    """),
            new Version(ReminderDefaults.PASSPORT_ID, Language.Croatian,
                    "Provjera putovnice za {{tripTitle}}",
                    """
                    <p>Pozdrav, {{firstName}},</p>
                    <p>Provjeravamo putovnice za <b>{{tripTitle}}</b> ({{tripDates}}).</p>
                    <p>Putnik {{travelerName}}: {{passportIssue}}</p>
                    <p>Mnoge zemlje traže putovnicu koja vrijedi još 6 mjeseci nakon povratka.
                    <a href="{{profileLink}}">Ažurirajte putovnicu na svom profilu</a> kad je budete imali.</p>
                    """),
            // ---------------------------------------------------------------- reminder-countdown
            // A count inside a sentence needs plural rules a {{token}} cannot apply (docs/i18n.md), so these two
            // put the number after a label instead of "N days".
            new Version(ReminderDefaults.COUNTDOWN_ID, Language.Spanish,
                    "{{tripTitle}}: días hasta la salida: {{daysUntilDeparture}}",
                    """
                    <p>Hola, {{firstName}}:</p>
                    <p>Días hasta el inicio de <b>{{tripTitle}}</b>: {{daysUntilDeparture}} ({{tripDates}}).</p>
                    <p><a href="{{itineraryLink}}">Ver el itinerario</a></p>
                    <p>¿Tienes preguntas antes de salir? Responde a este correo.</p>
                    """),
            new Version(ReminderDefaults.COUNTDOWN_ID, Language.Croatian,
                    "{{tripTitle}}: dana do polaska: {{daysUntilDeparture}}",
                    """
                    <p>Pozdrav, {{firstName}},</p>
                    <p>Dana do početka putovanja <b>{{tripTitle}}</b>: {{daysUntilDeparture}} ({{tripDates}}).</p>
                    <p><a href="{{itineraryLink}}">Pogledaj plan puta</a></p>
                    <p>Imate pitanja prije polaska? Samo odgovorite na ovaj e-mail.</p>
                    """));

    private StarterTranslations() {
    }

    /** The version of {@code baseId} in {@code language}; null when none ships (English always answers null). */
    public static Version of(final String baseId, final Language language) {
        for (final Version version : VERSIONS) {
            if (version.baseId().equals(baseId) && version.language() == language) {
                return version;
            }
        }
        return null;
    }

    /** The installed row's description: which language, and which starter it translates. */
    static String descriptionOf(final Version version) {
        return version.language().getDisplayName() + " version of " + version.baseId()
                + "; the tokens are the same.";
    }
}
